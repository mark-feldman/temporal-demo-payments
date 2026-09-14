package com.example.payouts.support

import com.example.payouts.activities.BankActivities
import com.example.payouts.activities.FxActivities
import com.example.payouts.activities.LedgerActivities
import com.example.payouts.activities.NotificationActivities
import com.example.payouts.activities.RailActivities
import com.example.payouts.activities.ValidationActivities
import com.example.payouts.app.TemporalConfig
import com.example.payouts.model.activity.*
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.workflow.ProcessPayoutRequest
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.workflow.PayoutWorkflowImpl
import com.example.payouts.workflow.TASK_QUEUE
import io.temporal.activity.Activity
import io.temporal.api.enums.v1.IndexedValueType
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.converter.DataConverter
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactoryOptions
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared scaffolding for the workflow tests.
 *
 * Two things here are load-bearing rather than convenience:
 *
 *  - The eight custom Search Attributes the workflow upserts have to be registered with the
 *    in-memory test server, or the first `upsertTypedSearchAttributes` fails the workflow task.
 *  - The client runs on the PRODUCTION DataConverter, so every test payload goes through
 *    `KotlinxJsonPayloadConverter` exactly as it does in the demo. A test that quietly fell
 *    back to Jackson would not exercise the single-concrete-@Serializable-param rule at all.
 */

/** Name -> type, matching the companion object in PayoutWorkflowImpl. */
val DEMO_SEARCH_ATTRIBUTES: Map<String, IndexedValueType> = mapOf(
    "payoutId" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
    "customerId" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
    "rail" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
    "region" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
    "amountMinor" to IndexedValueType.INDEXED_VALUE_TYPE_INT,
    "currency" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
    "businessStatus" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
    "scenarioName" to IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD,
)

/** The real bean method, not a lookalike. */
fun productionDataConverter(): DataConverter = TemporalConfig().mainDataConverter()

/**
 * Builds the test environment used by every workflow test.
 *
 * `TestWorkflowExtension` is not used here, despite being the documented JUnit 5 entry point,
 * because it invents a per-test task queue and this workflow cannot follow it: every activity
 * stub in PayoutWorkflowImpl pins `setTaskQueue(TASK_QUEUE)` explicitly, so activities are
 * always scheduled onto "payouts". With the extension's generated queue, the workflow starts,
 * schedules validatePayout onto "payouts", and parks forever because nothing polls it.
 *
 * Driving TestWorkflowEnvironment directly puts the workflow and its activities on the same
 * queue the demo uses, which is also what production does.
 */
fun newPayoutTestEnvironment(statusListener: BusinessStatusListener? = null): TestWorkflowEnvironment {
    val options = TestEnvironmentOptions.newBuilder()
        .setWorkflowClientOptions(
            WorkflowClientOptions.newBuilder()
                .setNamespace("default")
                .setDataConverter(productionDataConverter())
                .build(),
        )
        .apply { DEMO_SEARCH_ATTRIBUTES.forEach { (name, type) -> registerSearchAttribute(name, type) } }
        .apply {
            // The listener is how tests wait for a business state without polling.
            if (statusListener != null) {
                setWorkerFactoryOptions(
                    WorkerFactoryOptions.newBuilder().setWorkerInterceptors(statusListener).build(),
                )
            }
        }
        .build()
    return TestWorkflowEnvironment.newInstance(options)
}

/** Registers the workflow and the given activity stand-ins on the demo task queue, then starts. */
fun TestWorkflowEnvironment.startPayoutWorker(vararg activities: Any): Worker {
    val worker = newWorker(TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(PayoutWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(*activities)
    start()
    return worker
}

/** Mirrors what PayoutController builds, so tests exercise the real request shape. */
fun payoutRequest(
    payoutId: String = "po-000001",
    amountMinor: Long = 25_000,
    currency: String = "USD",
    customerId: String = "cust-0001",
    rail: Rail = Rail.HTTP,
    region: Region = Region.SG,
    scenarioName: String = "unit-test",
) = ProcessPayoutRequest(
    payoutId = payoutId,
    customerId = customerId,
    amount = Money(amountMinor, currency),
    rail = rail,
    region = region,
    scenarioName = scenarioName,
    idempotencyKey = "$payoutId-rail-1",
)

/**
 * A ScenarioStore bound to [file], never the demo's own.
 *
 * The path is read once, in a field initialiser, so the system property has to be set before
 * the constructor runs and is restored immediately afterwards. Anything else would leak the
 * path into the next test -- and the default, `.scenario-store.json` relative to CWD, is the
 * live demo store sitting in this very directory.
 */
fun scenarioStoreAt(file: Path): ScenarioStore {
    val previous = System.getProperty("demo.scenarioFile")
    System.setProperty("demo.scenarioFile", file.toAbsolutePath().toString())
    return try {
        ScenarioStore()
    } finally {
        if (previous == null) System.clearProperty("demo.scenarioFile")
        else System.setProperty("demo.scenarioFile", previous)
    }
}

/**
 * A ScenarioStore on a fresh throwaway path inside [dir].
 *
 * The file is deliberately NOT created. `Files.createTempFile` would leave a zero-byte file,
 * and `ScenarioStore.reloadIfChanged` treats "exists" as "worth parsing" -- so an empty file
 * fails to decode and logs `Could not read scenario store: ... had 'EOF' instead`. It then
 * logs it *again* on every subsequent miss, because `loadedAtMs` is only advanced on a
 * successful parse. An absent path takes the silent early return instead, which is also what
 * a real cold start looks like before the API has written anything.
 */
fun scenarioStoreIn(dir: Path): ScenarioStore =
    scenarioStoreAt(dir.resolve("scenario-${storeSequence.incrementAndGet()}.json"))

private val storeSequence = AtomicInteger()

/** One recorded activity invocation, including attempts that went on to fail. */
data class ActivityCall(
    val step: String,
    val payoutId: String,
    val attempt: Int,
    /** Set for submitToRail and reverseRailInstruction; blank elsewhere. */
    val idempotencyKey: String = "",
    /** Set for markPayout: the terminal outcome written to the ledger. */
    val outcome: String = "",
)

/**
 * Stand-in for the six activity implementations.
 *
 * It is deliberately NOT a Mockito mock: the workflow needs real, attempt-aware behaviour for
 * the retry and polling scenarios, and Kotlin's non-null return types make an unstubbed mock
 * fatal rather than merely empty. (PayoutWorkflowMockitoTest covers the Mockito style
 * separately, because that is the pattern the SDK documents.)
 *
 * Failure injection is delegated to the PRODUCTION [ScenarioStore.maybeFail], so there is one
 * source of truth for what FAIL_TRANSIENT and friends mean. The only behavioural difference
 * from `ActivityMock.run` is the missing 1-2.5s delay -- which exists to make the demo legible
 * and would merely make the suite slow.
 */
class RecordingActivities(
    private val scenarios: ScenarioStore,
) : ValidationActivities, LedgerActivities, FxActivities, RailActivities, BankActivities, NotificationActivities {

    val calls: MutableList<ActivityCall> = Collections.synchronizedList(mutableListOf())

    /**
     * Fail the first N attempts at releasing the reservation, then succeed.
     *
     * Not routed through ScenarioStore: its COMPENSATION_FAILS throws unconditionally, by
     * design, so a workflow under it never finishes compensating. A bounded count is what
     * proves the useful property -- that an uncapped retry policy eventually gets there.
     */
    var releaseFailures: Int = 0

    fun steps(): List<String> = synchronized(calls) { calls.map { it.step } }

    fun callsTo(step: String): List<ActivityCall> = synchronized(calls) { calls.filter { it.step == step } }

    /**
     * Records BEFORE injecting the failure -- unlike production, which logs after. Attempts
     * that end in a retryable failure have to be visible, or "the idempotency key is stable
     * across retries" could not be asserted at all.
     */
    private fun <T> run(
        step: String,
        payoutId: String,
        idempotencyKey: String = "",
        outcome: String = "",
        result: (Int) -> T,
    ): T {
        val attempt = Activity.getExecutionContext().info.attempt
        calls += ActivityCall(step, payoutId, attempt, idempotencyKey, outcome)
        scenarios.maybeFail(step, payoutId, attempt)
        return result(attempt)
    }

    override fun validatePayout(request: ValidatePayoutRequest) =
        run("validatePayout", request.payoutId) {
            ValidatePayoutResponse(valid = true, detail = "ok")
        }

    override fun reserveFunds(request: ReserveFundsRequest) =
        run("reserveFunds", request.payoutId) {
            ReserveFundsResponse(reservationId = "RES-${request.payoutId.takeLast(8)}")
        }

    override fun releaseReservedFunds(request: ReleaseFundsRequest) =
        run("releaseReservedFunds", request.payoutId) { attempt ->
            if (attempt <= releaseFailures) {
                throw ApplicationFailure.newFailure("ledger unavailable (attempt $attempt)", "LedgerUnavailable")
            }
            ReleaseFundsResponse(released = true)
        }

    override fun markPayout(request: MarkPayoutRequest) =
        run("markPayout", request.payoutId, outcome = request.outcome) { MarkPayoutResponse(recorded = true) }

    override fun validateFxQuote(request: ValidateFxQuoteRequest) =
        run("validateFxQuote", request.payoutId) {
            val rate = FX_RATES[request.amount.currency] ?: 1.0
            ValidateFxQuoteResponse(
                rate = rate,
                usdEquivalentMinor = (request.amount.amountMinor * rate).toLong(),
                // Fixed rather than System.currentTimeMillis: nothing reads it, and a moving
                // value in a recorded activity result is noise in a history diff.
                expiresAtEpochMs = 0L,
            )
        }

    override fun submitToRail(request: SubmitToRailRequest) =
        run("submitToRail", request.payoutId, request.idempotencyKey) { attempt ->
            SubmitToRailResponse(
                accepted = true,
                bankReference = "BANK-${request.rail}-${request.payoutId.substringAfter("po-")}",
                attempt = attempt,
            )
        }

    override fun reverseRailInstruction(request: ReverseRailRequest) =
        run("reverseRailInstruction", request.payoutId, request.idempotencyKey) {
            ReverseRailResponse(
                reversed = true,
                reversalReference = "REV-${request.rail}-${request.payoutId.substringAfter("po-")}",
            )
        }

    /** Mirrors BankActivitiesImpl: pending is a RETRYABLE failure, so the retry policy polls. */
    override fun pollBankStatus(request: BankStatusProbeRequest) =
        run("pollBankStatus", request.payoutId) { attempt ->
            val config = scenarios.get(request.payoutId)
            if (config.pollingNeverResolves || attempt <= config.pollsBeforeResolution) {
                throw ApplicationFailure.newFailure(
                    "bank still reports the instruction as pending (poll $attempt)",
                    "BankStatusPending",
                )
            }
            BankStatusProbeResponse(status = BankStatus.valueOf(config.resolvedStatus))
        }

    /**
     * The inline settlement. Deterministic here, unlike production's 80/20 roll: a test that
     * wants a refusal stages `behavior=REJECTED, step=settleWithBank` and gets it from
     * ScenarioStore.maybeFail, which is the same seam every other injected failure uses.
     * Rolling dice in the fake would make the two outcomes untestable rather than realistic.
     */
    override fun settleWithBank(request: BankSettlementRequest) =
        run("settleWithBank", request.payoutId, request.idempotencyKey) {
            BankSettlementResponse(status = BankStatus.COMPLETED)
        }

    override fun notify(request: NotifyRequest) =
        run("notify", request.payoutId) { NotifyResponse(sent = true) }

    private companion object {
        val FX_RATES = mapOf("USD" to 1.0, "SGD" to 0.74, "AUD" to 0.66, "CNY" to 0.14, "EUR" to 1.08)
    }
}
