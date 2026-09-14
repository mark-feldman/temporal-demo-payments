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
    /** Which activity step the behaviour applies to; blank means the rail submission. */
    val step: String = "submitToRail",
)

/**
 * Per-payout failure injection, read by ACTIVITIES only. Activity results are recorded in
 * history, so a replaying workflow replays the recorded outcome -- reading mutable config
 * from workflow code would be a non-determinism bug.
 *
 * Backed by a file because the demo includes killing the whole application. In-memory
 * config would silently reset on restart and turn a staged failure into a mystery success.
 */
@Component
class ScenarioStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }
    private val mapSerializer = MapSerializer(String.serializer(), ScenarioConfig.serializer())
    private val path: Path = Path.of(System.getProperty("demo.scenarioFile") ?: ".scenario-store.json")
    private val configs = ConcurrentHashMap<String, ScenarioConfig>()

    init {
        if (path.exists()) {
            runCatching { json.decodeFromString(mapSerializer, Files.readString(path)) }
                .onSuccess { configs.putAll(it); log.info("Restored {} scenario configs", it.size) }
                .onFailure { log.warn("Could not restore scenario store: {}", it.message) }
        }
    }

    fun put(payoutId: String, config: ScenarioConfig) {
        configs[payoutId] = config
        persist()
    }

    fun get(payoutId: String): ScenarioConfig = configs[payoutId] ?: ScenarioConfig()

    fun all(): Map<String, ScenarioConfig> = configs.toMap()

    fun clear() {
        configs.clear()
        persist()
    }

    /**
     * Throws per the configured behaviour, or returns normally. Attempt counting uses
     * Temporal's own per-execution counter rather than a server-side map: it resets
     * correctly and needs no cleanup between demo runs.
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

    private fun persist() {
        runCatching { Files.writeString(path, json.encodeToString(mapSerializer, configs.toMap())) }
            .onFailure { log.warn("Could not persist scenario store: {}", it.message) }
    }
}
