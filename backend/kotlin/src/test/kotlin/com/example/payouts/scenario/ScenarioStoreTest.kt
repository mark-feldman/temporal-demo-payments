package com.example.payouts.scenario

import com.example.payouts.support.scenarioStoreAt
import com.example.payouts.support.scenarioStoreIn
import io.temporal.failure.ApplicationFailure
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ScenarioStore is the failure-injection channel between the API JVM and the worker JVMs, and
 * the only place the demo's "make this one fail" behaviour is defined. Both halves matter:
 * what each Behavior throws, and that a worker started before a scenario was configured still
 * picks it up.
 */
class ScenarioStoreTest {

    @TempDir
    lateinit var tmp: Path

    private fun failureFrom(block: () -> Unit): ApplicationFailure =
        try {
            block()
            fail("expected an ApplicationFailure")
        } catch (e: ApplicationFailure) {
            e
        }

    // ---- behaviours ----

    @Test
    fun `PASS never fails`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.PASS))
        repeat(5) { attempt -> store.maybeFail("submitToRail", "po-1", attempt + 1) }
    }

    @Test
    fun `ACCEPTED_NO_CALLBACK never fails -- the bank stub drives that scenario`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.ACCEPTED_NO_CALLBACK))
        repeat(5) { attempt -> store.maybeFail("submitToRail", "po-1", attempt + 1) }
    }

    @Test
    fun `FAIL_TRANSIENT fails the configured number of attempts then passes`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.FAIL_TRANSIENT, transientFailures = 2))

        val first = failureFrom { store.maybeFail("submitToRail", "po-1", 1) }
        assertEquals("RailTransient", first.type)
        assertFalse(first.isNonRetryable, "transient means retryable, or the retry demo shows nothing")

        failureFrom { store.maybeFail("submitToRail", "po-1", 2) }

        // Attempt 3 is the one that succeeds -- which is why railAttempts reads 3, not 2.
        store.maybeFail("submitToRail", "po-1", 3)
        store.maybeFail("submitToRail", "po-1", 4)
    }

    @Test
    fun `FAIL_PERMANENT is non-retryable, so Temporal stops immediately`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT))

        val failure = failureFrom { store.maybeFail("submitToRail", "po-1", 1) }
        assertEquals("RailRejected", failure.type)
        assertTrue(failure.isNonRetryable)
        // The workflow classifies RAIL_PERMANENT by looking for "Rail" in the message.
        assertTrue(failure.message.orEmpty().contains("Rail"), "message drives failure classification")
    }

    @Test
    fun `REJECTED is a non-retryable bank rejection`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.REJECTED))

        val failure = failureFrom { store.maybeFail("submitToRail", "po-1", 1) }
        assertEquals("BankRejected", failure.type)
        assertTrue(failure.isNonRetryable)
    }

    @Test
    fun `COMPENSATION_FAILS only bites on the release step`() {
        val store = scenarioStoreIn(tmp)
        store.put(
            "po-1",
            ScenarioConfig(behavior = Behavior.COMPENSATION_FAILS, step = "releaseReservedFunds"),
        )

        val failure = failureFrom { store.maybeFail("releaseReservedFunds", "po-1", 1) }
        assertEquals("LedgerUnavailable", failure.type)
        assertFalse(failure.isNonRetryable, "compensation has to keep retrying, so this must be retryable")
    }

    // ---- targeting ----

    @Test
    fun `a behaviour applies only to its configured step`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))

        // Every other activity on the same payout runs clean.
        listOf("validatePayout", "reserveFunds", "validateFxQuote", "markPayout", "notify")
            .forEach { store.maybeFail(it, "po-1", 1) }

        failureFrom { store.maybeFail("submitToRail", "po-1", 1) }
    }

    @Test
    fun `an unconfigured payout gets the defaults and never fails`() {
        val store = scenarioStoreIn(tmp)
        val config = store.get("po-unknown")
        assertEquals(Behavior.PASS, config.behavior)
        assertEquals("submitToRail", config.step)
        assertEquals(3, config.pollsBeforeResolution)
        assertEquals("COMPLETED", config.resolvedStatus)
        assertFalse(config.pollingNeverResolves)
        store.maybeFail("submitToRail", "po-unknown", 1)
    }

    // ---- the API-to-worker channel ----

    @Test
    fun `a store created later reads what an earlier store wrote`() {
        val file = tmp.resolve("shared-store.json")
        val api = scenarioStoreAt(file)
        api.put("po-1", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, transientFailures = 7))

        // This is the worker JVM starting up after the API staged a scenario.
        val worker = scenarioStoreAt(file)
        assertEquals(Behavior.FAIL_PERMANENT, worker.get("po-1").behavior)
        assertEquals(7, worker.get("po-1").transientFailures)
    }

    @Test
    fun `a store already running picks up a write made after it started`() {
        val file = tmp.resolve("reload-store.json")
        val api = scenarioStoreAt(file)
        api.put("po-seed", ScenarioConfig(behavior = Behavior.PASS))

        // Worker is already up, and has loaded a file that says nothing about po-1.
        val worker = scenarioStoreAt(file)
        assertEquals(Behavior.PASS, worker.get("po-seed").behavior)
        assertEquals(Behavior.PASS, worker.get("po-1").behavior, "not configured yet -> defaults")

        api.put("po-1", ScenarioConfig(behavior = Behavior.FAIL_TRANSIENT, transientFailures = 4))

        // reloadIfChanged compares mtime in whole milliseconds, so two writes landing inside
        // the same millisecond are indistinguishable to it. Stamp the file forward instead of
        // sleeping to outrun the clock: deterministic, and it costs no wall-clock time.
        Files.setLastModifiedTime(
            file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1_000),
        )

        // Without the mtime reload, a worker started before the scenario existed would never
        // see it, and the staged failure would silently become a success.
        assertEquals(Behavior.FAIL_TRANSIENT, worker.get("po-1").behavior)
        assertEquals(4, worker.get("po-1").transientFailures)
    }

    @Test
    fun `a reused payout id gets the scenario staged now, not the one it replaced`() {
        val file = tmp.resolve("reuse-store.json")
        val api = scenarioStoreAt(file)
        // An earlier run staged this id to fail permanently.
        api.put("po-044916", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT))

        // A worker loads that file, so the id is already in its map.
        val worker = scenarioStoreAt(file)
        assertEquals(Behavior.FAIL_PERMANENT, worker.get("po-044916").behavior)

        // The payout id counter is seeded from the clock, so ids come round again after a
        // restart. This time the id belongs to a payout staged to pass.
        api.put("po-044916", ScenarioConfig(behavior = Behavior.PASS))
        Files.setLastModifiedTime(
            file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 1_000),
        )

        assertEquals(
            Behavior.PASS,
            worker.get("po-044916").behavior,
            "the worker already held this id, so a refresh that ran only for missing keys " +
                "served the previous run's config -- a payout staged to pass failed instead",
        )
    }

    @Test
    fun `all exposes what has been staged and clear empties it`() {
        val store = scenarioStoreIn(tmp)
        store.put("po-1", ScenarioConfig(behavior = Behavior.FAIL_TRANSIENT))
        store.put("po-2", ScenarioConfig(behavior = Behavior.REJECTED))

        assertEquals(setOf("po-1", "po-2"), store.all().keys)
        assertEquals(Behavior.REJECTED, store.all()["po-2"]?.behavior)

        store.clear()
        assertTrue(store.all().isEmpty())
        assertNull(store.all()["po-1"])
        // Cleared config means default behaviour, not an error.
        store.maybeFail("submitToRail", "po-1", 1)
    }

    @Test
    fun `polling configuration survives a write and a reload`() {
        val file = tmp.resolve("polling-store.json")
        scenarioStoreAt(file).put(
            "po-1",
            ScenarioConfig(
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollsBeforeResolution = 5,
                resolvedStatus = "REJECTED",
                pollingNeverResolves = true,
            ),
        )

        val reloaded = scenarioStoreAt(file).get("po-1")
        assertEquals(5, reloaded.pollsBeforeResolution)
        assertEquals("REJECTED", reloaded.resolvedStatus)
        assertTrue(reloaded.pollingNeverResolves)
    }
}
