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
 * Two requirements:
 *
 *  - The custom Search Attributes the workflow upserts must be registered with the in-memory
 *    test server, or the first `upsertTypedSearchAttributes` fails the workflow task.
 *  - The client runs on the production DataConverter, so every test payload goes through
 *    `KotlinxJsonPayloadConverter` as it does in the demo, exercising the
 *    single-concrete-@Serializable-param rule.
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
 * `TestWorkflowExtension` is not used: it invents a per-test task queue, and every activity
 * stub in PayoutWorkflowImpl pins `setTaskQueue(TASK_QUEUE)`, so activities are always
 * scheduled onto "payouts". Under the extension's generated queue the workflow parks at
 * VALIDATING because nothing polls "payouts".
 *
 * Driving TestWorkflowEnvironment directly puts the workflow and its activities on the same
 * queue the demo uses.
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
 * A ScenarioStore bound to [file] rather than the demo's own.
 *
 * `ScenarioStore` reads its path once, in a field initialiser, so the system property is set
 * before the constructor runs and restored immediately afterwards. The default,
 * `.scenario-store.json` relative to CWD, is the live demo store.
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
 * The file is not created. `ScenarioStore.reloadIfChanged` parses any path that exists, so a
 * zero-byte file fails to decode and logs on every miss, since `loadedAtMs` only advances on a
 * successful parse. An absent path takes the silent early return, as on a cold start.
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
 * Not a Mockito mock: the retry and polling scenarios need attempt-aware behaviour, and
 * Kotlin's non-null return types make an unstubbed mock fatal rather than empty.
 * PayoutWorkflowMockitoTest covers the Mockito style separately.
 *
 * Failure injection is delegated to the production [ScenarioStore.maybeFail], so
 * FAIL_TRANSIENT and the others mean one thing. The only behavioural difference from
 * `ActivityMock.run` is the omitted 1-2.5s delay.
 */
class RecordingActivities(
    private val scenarios: ScenarioStore,
) : ValidationActivities, LedgerActivities, FxActivities, RailActivities, BankActivities, NotificationActivities {

    val calls: MutableList<ActivityCall> = Collections.synchronizedList(mutableListOf())

    /**
     * Fail the first N attempts at releasing the reservation, then succeed.
     *
     * Not routed through ScenarioStore, whose COMPENSATION_FAILS throws unconditionally, so a
     * workflow under it never finishes compensating. A bounded count shows that an uncapped
     * retry policy completes.
     */
    var releaseFailures: Int = 0

    fun steps(): List<String> = synchronized(calls) { calls.map { it.step } }

    fun callsTo(step: String): List<ActivityCall> = synchronized(calls) { calls.filter { it.step == step } }

    /**
     * Records the call before injecting the failure, so attempts that end in a retryable
     * failure are visible to assertions about retry behaviour.
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

    /** Mirrors BankActivitiesImpl: pending is a retryable failure, so the retry policy polls. */
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
     * The inline settlement, always COMPLETED here rather than production's weighted roll. A
     * test that wants a refusal stages `behavior=REJECTED, step=settleWithBank`, which
     * ScenarioStore.maybeFail applies like every other injected failure.
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
