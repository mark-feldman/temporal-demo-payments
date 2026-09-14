package com.example.payouts.scenario

import io.temporal.failure.ApplicationFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

enum class Behavior {
    PASS,
    FAIL_TRANSIENT,
    FAIL_PERMANENT,
    ACCEPTED_NO_CALLBACK,
    REJECTED,
    COMPENSATION_FAILS,
}

@Serializable
data class ScenarioConfig(
    val behavior: Behavior = Behavior.PASS,
    /** For FAIL_TRANSIENT: fail this many attempts, then pass. */
    val transientFailures: Int = 2,
    /** Which activity step the behaviour applies to. Defaults to the rail submission. */
    val step: String = "submitToRail",
    /** How many polls return "still pending" before the bank gives a real answer. */
    val pollsBeforeResolution: Int = 3,
    /** What the bank eventually reports once polling resolves. */
    val resolvedStatus: String = "COMPLETED",
    /** When true the bank never answers, so polling exhausts its retries. */
    val pollingNeverResolves: Boolean = false,
)

/**
 * Per-payout failure injection, read by activities only. Activity results are recorded in
 * history, so a replaying workflow replays the recorded outcome; reading mutable config from
 * workflow code would be a non-determinism bug.
 *
 * Backed by a file, which survives a process restart and is the channel between the API (which
 * writes it) and the worker JVMs (whose activities read it). A worker reloads on file change.
 */
@Component
class ScenarioStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val mapSerializer = MapSerializer(String.serializer(), ScenarioConfig.serializer())
    private val path: Path = Path.of(System.getProperty("demo.scenarioFile") ?: ".scenario-store.json")
    private val configs = ConcurrentHashMap<String, ScenarioConfig>()

    @Volatile private var loadedAtMs = 0L

    init { reloadIfChanged() }

    /**
     * The API process writes this file; worker processes read it. Reload on mtime change so
     * a worker started before a scenario was configured still sees it.
     */
    private fun reloadIfChanged() {
        if (!path.exists()) return
        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
        if (modified == loadedAtMs) return
        runCatching { json.decodeFromString(mapSerializer, Files.readString(path)) }
            .onSuccess { configs.putAll(it); loadedAtMs = modified }
            .onFailure { log.warn("Could not read scenario store: {}", it.message) }
    }

    fun put(payoutId: String, config: ScenarioConfig) {
        configs[payoutId] = config
        persist()
    }

    /**
     * Always checks the file's mtime first. Refreshing only when the key is absent served a
     * stale config whenever a payout id was reused: the counter is seeded from the clock, so
     * ids repeat across restarts, and a worker holding an entry from a previous run never
     * looked again. A PASS payout then failed with whatever the old entry said.
     */
    fun get(payoutId: String): ScenarioConfig {
        reloadIfChanged()
        return configs[payoutId] ?: ScenarioConfig()
    }

    fun all(): Map<String, ScenarioConfig> = configs.toMap()

    fun clear() {
        configs.clear()
        persist()
    }

    /**
     * Throws per the configured behaviour, or returns normally. [attempt] is Temporal's own
     * per-execution attempt counter, passed in by the caller.
     */
    fun maybeFail(step: String, payoutId: String, attempt: Int) {
        val config = get(payoutId)
        if (config.step != step) return
        when (config.behavior) {
            Behavior.PASS, Behavior.ACCEPTED_NO_CALLBACK -> Unit
            Behavior.FAIL_TRANSIENT ->
                if (attempt <= config.transientFailures) {
                    throw ApplicationFailure.newFailure(
                        "$step failed transiently on attempt $attempt (rail unavailable)",
                        "RailTransient",
                    )
                }
            Behavior.FAIL_PERMANENT ->
                throw ApplicationFailure.newNonRetryableFailure(
                    "$step rejected permanently by the rail",
                    "RailRejected",
                )
            Behavior.REJECTED ->
                throw ApplicationFailure.newNonRetryableFailure(
                    "bank rejected the instruction",
                    "BankRejected",
                )
            Behavior.COMPENSATION_FAILS ->
                if (step == "releaseReservedFunds") {
                    throw ApplicationFailure.newFailure("ledger unavailable", "LedgerUnavailable")
                }
        }
    }

    /**
     * Written to a sibling file and moved into place, so a worker reading it concurrently sees
     * either the old file or the new one, never a half-written one.
     */
    @Synchronized
    private fun persist() {
        runCatching {
            val tmp = path.resolveSibling("${'$'}{path.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(mapSerializer, configs.toMap()))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            loadedAtMs = Files.getLastModifiedTime(path).toMillis()
        }
            .onFailure { log.warn("Could not persist scenario store: {}", it.message) }
    }
}
