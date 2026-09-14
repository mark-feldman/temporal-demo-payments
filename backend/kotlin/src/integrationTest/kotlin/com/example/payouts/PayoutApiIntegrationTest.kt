package com.example.payouts

import com.example.payouts.api.StartPayoutBody
import com.example.payouts.api.StartPayoutResult
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.workflow.ApprovalDecisionRequest
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.scenario.Behavior
import com.example.payouts.support.BusinessStatusListener
import com.example.payouts.support.DEMO_SEARCH_ATTRIBUTES
import com.example.payouts.worker.WorkerSupervisor
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The demo backend contract, asserted in-JVM.
 *
 * This mirrors `scripts/contract-test.sh` assertion for assertion, but needs nothing running:
 * no dev server, no Caddy, no forked worker. The whole Spring context boots -- real
 * controllers, real activity beans, the real ScenarioStore, the production kotlinx
 * DataConverter -- against the SDK's in-memory test server.
 *
 * There is no time skipping here: the activity stubs sleep and a payout takes several
 * seconds, so the deadline paths (30s approval, 45s bank) live in the unit suite instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Execution(ExecutionMode.CONCURRENT)
// Spring Boot switches metrics export off in tests by default, so without this there is no
// PrometheusMeterRegistry, no /actuator/prometheus endpoint, and the scrape 404s.
@AutoConfigureObservability
class PayoutApiIntegrationTest {

    /**
     * WorkerSupervisor forks a real JVM from build/libs on ApplicationReadyEvent, pointed at
     * the real 127.0.0.1:7233. Mocking it keeps the suite from spawning stray workers against
     * live demo state; the in-process worker started by `start-workers: true` does the work.
     */
    @MockitoBean
    private lateinit var workerSupervisor: WorkerSupervisor

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    @Qualifier("temporalTestWorkflowEnvironment")
    private lateinit var testEnv: TestWorkflowEnvironment

    @Autowired
    private lateinit var statusEvents: BusinessStatusListener

    @Autowired
    private lateinit var meters: MeterRegistry

    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    /**
     * The starter passes every WorkerInterceptor bean straight through to the WorkerFactory
     * (`AutoConfigurationUtils.chooseWorkerInterceptors` returns the list unfiltered), so the
     * same listener the unit suite uses works here with no other wiring.
     */
    @TestConfiguration
    class StatusEventsConfiguration {
        @Bean
        fun businessStatusListener() = BusinessStatusListener()
    }

    /**
     * The workflow upserts eight custom Search Attributes, and the in-memory server rejects
     * any it does not know. The Spring autoconfiguration builds TestEnvironmentOptions
     * without them, so they have to be registered here -- idempotent, hence @BeforeEach.
     */
    @BeforeEach
    fun registerSearchAttributes() {
        DEMO_SEARCH_ATTRIBUTES.forEach { (name, type) ->
            runCatching { testEnv.registerSearchAttribute(name, type) }
        }
    }

    // ---- helpers ----

    private fun url(path: String) = "http://localhost:$port/demo-api$path"

    private fun start(body: StartPayoutBody): StartPayoutResult =
        requireNotNull(rest.postForObject(url("/payouts"), body, StartPayoutResult::class.java))

    private fun statusJson(workflowId: String): Map<*, *> {
        val raw = rest.getForObject(url("/payouts/$workflowId/status"), String::class.java)
        return mapper.readValue(raw, Map::class.java)
    }

    /**
     * Blocks until the workflow transitions into one of [wanted], then reads its state once
     * over HTTP.
     *
     * Event-driven, not polled. The in-process worker runs [BusinessStatusListener], which the
     * workflow's own `upsertTypedSearchAttributes` call notifies at the moment of the
     * transition. The single HTTP GET that follows is the assertion; the listener only decides
     * when to make it, so the contract is checked over the same API the frontend uses.
     *
     * Every state waited on here is one the workflow parks on -- the two AWAITING_* states
     * and the terminal ones -- so the single read cannot land after a further transition.
     */
    private fun awaitStatus(
        workflowId: String,
        vararg wanted: BusinessStatus,
        timeout: Duration = Duration.ofSeconds(90),
    ): Map<*, *> {
        statusEvents.await(workflowId, *wanted, timeout = timeout)
        val snapshot = statusJson(workflowId)
        check(snapshot["status"] in wanted.map { it.name }) {
            "workflow '$workflowId' reported ${snapshot["status"]} over HTTP after " +
                "transitioning into one of ${wanted.toList()}. States seen: " +
                statusEvents.seen(workflowId)
        }
        return snapshot
    }

    private fun signalBank(workflowId: String, status: BankStatus) {
        rest.postForObject(
            url("/payouts/$workflowId/bank-status"),
            BankStatusUpdateRequest(status),
            String::class.java,
        )
    }

    // ---- 1: health ----

    @Test
    fun `health reports the SDK language, task queue, namespace and worker status`() {
        val health = rest.getForObject(url("/health"), Map::class.java)
        assertEquals("Kotlin", health["sdkLanguage"])
        assertEquals("payouts", health["taskQueue"])
        assertEquals("default", health["namespace"])
        assertEquals("UP", health["worker"])
        assertEquals(true, health["serverReachable"])
    }

    // ---- 2 + 4: start, query, and the bank callback ----

    @Test
    fun `a payout starts, is queryable, and the bank callback drives it to completion`() {
        val started = start(StartPayoutBody(scenario = "it-success", amountMinor = 25_000))
        assertTrue(started.payoutId.startsWith("po-"))
        assertEquals("payout-it-success-${started.payoutId}", started.workflowId)
        assertTrue(started.runId.isNotBlank())
        assertContains(started.temporalUrl, started.workflowId)

        awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        signalBank(started.workflowId, BankStatus.COMPLETED)
        assertEquals("COMPLETED", awaitStatus(started.workflowId, BusinessStatus.COMPLETED)["status"])
    }

    // ---- 3: the approval signal ----

    @Test
    fun `the approval signal releases a payout that is waiting on a human`() {
        val started = start(StartPayoutBody(scenario = "it-approval", amountMinor = 250_000))
        val waiting = awaitStatus(started.workflowId, BusinessStatus.AWAITING_APPROVAL)
        assertEquals("SENIOR", waiting["approvalTier"])

        val ack = rest.postForObject(
            url("/payouts/${started.workflowId}/approval"),
            ApprovalDecisionRequest(approved = true, approver = "integration-test"),
            Map::class.java,
        )
        assertEquals(true, ack["sent"])

        awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION, BusinessStatus.COMPLETED)
        signalBank(started.workflowId, BankStatus.COMPLETED)
        assertEquals("COMPLETED", awaitStatus(started.workflowId, BusinessStatus.COMPLETED)["status"])
    }

    // ---- low-value payouts settle inline, with no callback at all ----

    @Test
    fun `a low-value payout resolves over HTTP without any signal being sent`() {
        // No signal is sent. The assertion is on the path, not the outcome: this runs the
        // production activity, whose settle-or-refuse split is weighted, so either outcome is
        // correct here. The unit suite covers each one deterministically through the
        // injected-failure seam.
        val started = start(StartPayoutBody(scenario = "it-inline", amountMinor = 4_200))
        val final = awaitStatus(started.workflowId, BusinessStatus.COMPLETED, BusinessStatus.FAILED)

        @Suppress("UNCHECKED_CAST")
        val history = final["history"] as List<String>
        assertTrue(
            history.any { it.startsWith("SETTLING_WITH_BANK") },
            "the inline path has to be visible in the timeline, not merely implied: $history",
        )
        assertTrue(
            history.none { it.startsWith("AWAITING_BANK_CONFIRMATION") },
            "a low-value payout must not park on a callback: $history",
        )
        // Either outcome has to be recorded coherently.
        when (final["status"]) {
            "COMPLETED" -> assertEquals("NONE", final["failureCategory"])
            else -> assertEquals("BANK_REJECTED", final["failureCategory"], "a refusal, not some other failure")
        }
    }

    // ---- 5: transient failure produces retries ----

    @Test
    fun `a transient rail failure retries and the attempt count is visible`() {
        val started = start(
            StartPayoutBody(
                scenario = "it-retry",
                amountMinor = 25_000,
                behavior = Behavior.FAIL_TRANSIENT,
                transientFailures = 2,
            ),
        )
        val parked = awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        assertEquals(3, parked["railAttempts"], "two failures then the attempt that lands")

        signalBank(started.workflowId, BankStatus.COMPLETED)
        awaitStatus(started.workflowId, BusinessStatus.COMPLETED)
    }

    // ---- 6: permanent failure produces compensation ----

    @Test
    fun `a permanent rail rejection fails the payout and compensates`() {
        val started = start(
            StartPayoutBody(scenario = "it-permanent", amountMinor = 25_000, behavior = Behavior.FAIL_PERMANENT),
        )
        val final = awaitStatus(started.workflowId, BusinessStatus.FAILED, BusinessStatus.CANCELLED)

        assertEquals("FAILED", final["status"])
        assertEquals("RAIL_PERMANENT", final["failureCategory"])
        @Suppress("UNCHECKED_CAST")
        val history = final["history"] as List<String>
        assertTrue(history.any { it.startsWith("COMPENSATED") }, "the unwind must be recorded: $history")
    }

    // ---- 7: an absent callback resolves by polling ----

    @Test
    fun `an unknown callback resolves by polling the bank, without escalating`() {
        val started = start(
            StartPayoutBody(
                scenario = "it-poll-ok",
                amountMinor = 25_000,
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollsBeforeResolution = 2,
                resolvedStatus = "COMPLETED",
            ),
        )
        awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        signalBank(started.workflowId, BankStatus.UNKNOWN)

        val final = awaitStatus(started.workflowId, BusinessStatus.COMPLETED, BusinessStatus.FAILED, timeout = Duration.ofSeconds(120))
        assertEquals("COMPLETED", final["status"])
        @Suppress("UNCHECKED_CAST")
        val history = final["history"] as List<String>
        assertTrue(history.any { it.startsWith("POLLING_BANK_STATUS") }, "it polled rather than escalating")
    }

    // ---- 7b: polling resolves to a rejection ----

    @Test
    fun `polling that resolves to a rejection compensates`() {
        val started = start(
            StartPayoutBody(
                scenario = "it-poll-reject",
                amountMinor = 25_000,
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollsBeforeResolution = 1,
                resolvedStatus = "REJECTED",
            ),
        )
        awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        signalBank(started.workflowId, BankStatus.UNKNOWN)

        val final = awaitStatus(started.workflowId, BusinessStatus.FAILED, BusinessStatus.COMPLETED, timeout = Duration.ofSeconds(120))
        assertEquals("FAILED", final["status"])
        assertEquals("BANK_REJECTED", final["failureCategory"])
    }

    // ---- 7c + 7e: exhausted polling, and the reversal during the unwind ----

    @Test
    fun `exhausted polling compensates, is flagged UNKNOWN_BANK_STATUS, and reverses at the bank`() {
        val started = start(
            StartPayoutBody(
                scenario = "it-poll-never",
                amountMinor = 25_000,
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollingNeverResolves = true,
            ),
        )
        awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        signalBank(started.workflowId, BankStatus.UNKNOWN)

        // 7-10 polls with backoff, all in real time, then the unwind.
        val final = awaitStatus(started.workflowId, BusinessStatus.FAILED, BusinessStatus.COMPLETED, timeout = Duration.ofSeconds(240))
        assertEquals("FAILED", final["status"])
        // The flag records that the instruction may already have settled.
        assertEquals("UNKNOWN_BANK_STATUS", final["failureCategory"])
        assertTrue(
            (final["reversalReference"] as String).isNotBlank(),
            "the unwind reverses at the bank before releasing our own reservation",
        )
    }

    // ---- 7d: the response shape never changes with the values in it ----

    @Test
    fun `the status response always carries every field, even at its defaults`() {
        val started = start(StartPayoutBody(scenario = "it-shape", amountMinor = 25_000))
        val body = statusJson(started.workflowId)

        // kotlinx omits fields still holding their declared default unless encodeDefaults is
        // on, which drops approvalTier and failureCategory from the response. Only observable
        // over HTTP.
        listOf(
            "payoutId", "status", "currentStep", "approvalTier", "failureCategory",
            "railAttempts", "bankReference", "usdEquivalentMinor", "reversalReference", "history",
        ).forEach { field ->
            assertTrue(body.containsKey(field), "status response is missing '$field': ${body.keys}")
        }
        assertEquals("NONE", body["approvalTier"])
        assertEquals("NONE", body["failureCategory"])

        signalBank(started.workflowId, BankStatus.COMPLETED)
    }

    @Test
    fun `an unknown workflow id returns 404 rather than an error page`() {
        val response = rest.getForEntity(url("/payouts/does-not-exist/status"), String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    // ---- 8: metrics ----

    @Test
    fun `the metrics endpoint serves business counters alongside SDK metrics`() {
        // SDK metrics reach Micrometer through a Tally reporter on a 1s flush, so they are
        // genuinely not there at context startup. Rather than re-scrape on a timer, subscribe
        // to the registry: onMeterAdded fires the moment the first temporal_* meter is
        // registered. Existing meters are checked first, because a listener registered after
        // the fact never hears about them.
        val firstSdkMeter = CompletableFuture<String>()
        meters.meters
            .firstOrNull { it.id.name.startsWith(SDK_METRIC_PREFIX) }
            ?.let { firstSdkMeter.complete(it.id.name) }
        meters.config().onMeterAdded { meter ->
            if (meter.id.name.startsWith(SDK_METRIC_PREFIX)) firstSdkMeter.complete(meter.id.name)
        }

        start(StartPayoutBody(scenario = "it-metrics", amountMinor = 25_000))
        firstSdkMeter.get(30, TimeUnit.SECONDS)

        val metrics = rest.getForObject(url("/actuator/prometheus"), String::class.java).orEmpty()
        assertContains(metrics, "payout_workflows_started_total")
        assertTrue(
            metrics.contains("temporal_"),
            "SDK metrics must land in the same registry as the business counters, so one " +
                "scrape returns both",
        )
    }

    // ---- the scenario store is the API-to-worker channel ----

    @Test
    fun `starting a payout stages its scenario config for the worker to read`() {
        val started = start(
            StartPayoutBody(
                scenario = "it-scenarios",
                amountMinor = 25_000,
                behavior = Behavior.FAIL_TRANSIENT,
                transientFailures = 1,
            ),
        )
        val scenarios = rest.getForObject(url("/scenarios"), Map::class.java)
        val staged = scenarios[started.payoutId] as Map<*, *>
        assertEquals("FAIL_TRANSIENT", staged["behavior"])
        assertEquals(1, staged["transientFailures"])
        assertEquals("submitToRail", staged["step"])

        awaitStatus(started.workflowId, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        signalBank(started.workflowId, BankStatus.COMPLETED)
    }

    private companion object {
        const val SDK_METRIC_PREFIX = "temporal"
    }
}
