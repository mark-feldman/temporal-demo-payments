package com.example.payouts.workflow

import com.example.payouts.app.StreamCodec
import com.example.payouts.model.domain.PaymentOutcome
import com.example.payouts.model.domain.PaymentStage
import com.example.payouts.model.domain.StageState
import com.example.payouts.model.memo.*
import com.example.payouts.model.stream.ProgressEvent
import com.example.payouts.model.stream.RetryEvent
import com.example.payouts.model.stream.StageEvent
import com.example.payouts.model.stream.StageEventKind
import com.example.payouts.model.stream.Topics
import com.example.payouts.model.workflow.ProcessPaymentResponse
import com.example.payouts.model.workflow.StreamedPaymentResponse
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.support.*
import io.temporal.api.enums.v1.EventType
import io.temporal.api.sdk.v1.WorkflowMetadata
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionDescription
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowOptions
import io.temporal.failure.CanceledFailure
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.testing.WorkflowReplayer
import io.temporal.workflowstreams.SubscribeOptions
import io.temporal.workflowstreams.WorkflowStreamClient
import io.temporal.workflowstreams.WorkflowStreamItem
import io.temporal.workflowstreams.WorkflowStreamListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamedPaymentWorkflowTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var scenarios: ScenarioStore
    private lateinit var notifier: RecordingActivities
    private lateinit var starts: WorkflowStartListener
    private lateinit var env: TestWorkflowEnvironment

    private val client: WorkflowClient get() = env.workflowClient
    private val request = streamedPaymentRequest()
    private val workflowId = "payout-streamed-${request.payoutId}"
    private val processingId = processingWorkflowIdFor(workflowId)

    @BeforeEach
    fun setUp() {
        scenarios = scenarioStoreIn(tmp)
        notifier = RecordingActivities(scenarios)
        starts = WorkflowStartListener()
        env = newStreamedPaymentTestEnvironment(starts)
    }

    @AfterEach
    fun tearDown() = env.close()

    @Test
    fun `A receives every status update B publishes, in order, and acknowledges`() {
        env.startStreamedPaymentWorker(scenarios, notifier)

        val result = run()

        // 7 stages x STARTED/PASSED, plus OUTCOME.
        assertEquals(PaymentOutcome.SETTLED, result.outcome)
        assertEquals(15, result.statusUpdatesReceived)
        assertEquals(0, result.gaps)

        val a = describe(workflowId)
        val b = describe(processingId)
        val received = a.memo<StagesMemo>(MemoKeys.STAGES).entries
        val published = b.memo<StagesMemo>(MemoKeys.STAGES).entries
        assertEquals((0..14).toList(), received.map { it.seq })
        assertEquals(published.map { it.key() }, received.map { it.key() })

        assertEquals(RelayMemo(processingId, b.execution.runId, 15, 0, 0, a.memo<RelayMemo>(MemoKeys.RELAY).lastOffset, true),
            a.memo<RelayMemo>(MemoKeys.RELAY))
        assertEquals(StreamMemo(Topics.ALL, 15, 14, AckState.RECEIVED), b.memo<StreamMemo>(MemoKeys.STREAM))
        assertEquals(PaymentOutcome.SETTLED, a.memo<OutcomeMemo>(MemoKeys.OUTCOME).outcome)
        assertEquals(a.memo<OutcomeMemo>(MemoKeys.OUTCOME), b.memo<OutcomeMemo>(MemoKeys.OUTCOME))
        assertEquals("Payment confirmed", a.memo<NotificationMemo>(MemoKeys.NOTIFICATION).message)
        assertEquals(workflowId, b.memo<PaymentMemo>(MemoKeys.PAYMENT).parentWorkflowId)

        val processed = client.newUntypedWorkflowStub(processingId).getResult(ProcessPaymentResponse::class.java)
        assertTrue(processed.acknowledged, "B returned without A's acknowledgement")
        assertEquals(1, notifier.callsTo("notify").size)

        val bDetails = currentDetails(processingId)
        assertTrue("acknowledged by A" in bDetails, bDetails)
        assertTrue("✓ settlement" in bDetails, bDetails)
        assertTrue("Customer notified: Payment confirmed" in currentDetails(workflowId))

        // The acknowledgement deadline is cancelled, not left as an unresolved TimerStarted.
        val timers = client.fetchHistory(processingId).events.map { it.eventType }
            .filter { it == EventType.EVENT_TYPE_TIMER_STARTED || it == EventType.EVENT_TYPE_TIMER_CANCELED }
        assertEquals(listOf(EventType.EVENT_TYPE_TIMER_STARTED, EventType.EVENT_TYPE_TIMER_CANCELED), timers)

        // Each poll's history event names its subscriber; here the relay is the only one.
        val pollers = client.fetchHistory(processingId).events
            .filter { it.hasWorkflowExecutionUpdateAcceptedEventAttributes() }
            .map { it.workflowExecutionUpdateAcceptedEventAttributes.acceptedRequest.meta.identity }
        assertEquals(setOf("A · relay activity · attempt 1"), pollers.toSet())

        assertBothReplay()
    }

    @Test
    fun `the relay runs through its heartbeat ticks in a single attempt`() {
        // 21 steps at 150ms keep B running for over 3s, past the relay's 2s heartbeat tick.
        env.startStreamedPaymentWorker(scenarios, notifier, stepDelay = 150.milliseconds)

        val result = run()

        assertEquals(15, result.statusUpdatesReceived)
        assertEquals(0, describe(workflowId).memo<RelayMemo>(MemoKeys.RELAY).duplicatesDropped)
        val relayAttempts = client.fetchHistory(workflowId).events
            .filter { it.hasActivityTaskStartedEventAttributes() }
            .map { it.activityTaskStartedEventAttributes.attempt }
        assertEquals(listOf(1, 1), relayAttempts, "the relay and the notification each ran once")
    }

    @Test
    fun `B answers every poll before it closes, including those of a subscriber that stays open`() {
        env.startStreamedPaymentWorker(scenarios, notifier)
        WorkflowClient.start(stub()::requestPayment, request)
        starts.await(processingId)

        // Subscribed for the whole run, as an open page is. With no cooldown it polls again as soon
        // as a poll returns, so a poll is waiting when B finishes.
        WorkflowStreamClient.newInstance(client, processingId, StreamCodec.clientOptions()).use { stream ->
            val options = SubscribeOptions.newBuilder().setPollCooldown(Duration.ZERO).build()
            val subscription = stream.subscribe(options, WorkflowStreamListener { null })
            assertEquals(PaymentOutcome.SETTLED, resultOf().outcome)
            subscription.doneFuture.get(30, TimeUnit.SECONDS)
        }

        val events = client.fetchHistory(processingId).events
        val accepted = events.count { it.hasWorkflowExecutionUpdateAcceptedEventAttributes() }
        assertTrue(accepted > 0)
        assertEquals(accepted, events.count { it.hasWorkflowExecutionUpdateCompletedEventAttributes() })
    }

    @Test
    fun `a retried stage leaves its failed attempt's progress in the stream, behind a retry event`() {
        scenarios.put(request.payoutId, ScenarioConfig(Behavior.FAIL_TRANSIENT, transientFailures = 1, step = "transferFunds"))
        env.startStreamedPaymentWorker(scenarios, notifier)

        val items = startAndSubscribe()
        val result = resultOf()

        val retries = items.filter { it.topic == Topics.RETRY }.map { StreamCodec.decode<RetryEvent>(it) }
        assertEquals(listOf(PaymentStage.FUNDS_TRANSFER to 2), retries.map { it.stage to it.attempt })
        val firstAttempt = items.filter { it.topic == Topics.PROGRESS }
            .map { StreamCodec.decode<ProgressEvent>(it) }
            .filter { it.stage == PaymentStage.FUNDS_TRANSFER && it.attempt == 1 }
        // The injected failure fires before the last of three steps.
        assertEquals(listOf(1, 2), firstAttempt.map { it.step })

        // The status topic is untouched by the retry, and it is exactly what A received.
        val status = items.filter { it.topic == Topics.STATUS }.map { StreamCodec.decode<StageEvent>(it) }
        val received = describe(workflowId).memo<StagesMemo>(MemoKeys.STAGES).entries
        assertEquals(status.map { Triple(it.seq, it.stage, it.state) }, received.map { Triple(it.seq, it.stage, it.state) })
        assertEquals(15, result.statusUpdatesReceived)
        assertEquals(PaymentOutcome.SETTLED, result.outcome)
    }

    @Test
    fun `a relay retry resumes from its heartbeat and A drops what it re-delivers`() {
        env.startStreamedPaymentWorker(scenarios, notifier, relay = FailingFirstAttemptRelay(relayFirst = 3))

        val result = run()

        val relay = describe(workflowId).memo<RelayMemo>(MemoKeys.RELAY)
        // Attempt 1 delivered seq 0-2 but recorded only seq 0's offset, so attempt 2 re-sends 1 and 2.
        assertEquals(2, relay.duplicatesDropped)
        assertEquals(15, relay.received)
        assertEquals(0, relay.gaps)
        assertTrue(relay.ackSent)
        assertEquals(15, result.statusUpdatesReceived)
        assertEquals(PaymentOutcome.SETTLED, result.outcome)
    }

    @Test
    fun `when the real relay's signal fails, the attempt fails and the retry fills the rest in`() {
        env.close()
        env = newStreamedPaymentTestEnvironment(clientInterceptors = listOf(FailingStageUpdateSignal(seq = 3)))
        env.startStreamedPaymentWorker(scenarios, notifier)

        val result = run()

        // Attempt 2 resumes from attempt 1's last heartbeat, so it may re-send some of seq 0-2.
        assertEquals(15, result.statusUpdatesReceived)
        assertEquals(0, result.gaps)
        assertEquals(PaymentOutcome.SETTLED, result.outcome)
        val pollers = client.fetchHistory(processingId).events
            .filter { it.hasWorkflowExecutionUpdateAcceptedEventAttributes() }
            .map { it.workflowExecutionUpdateAcceptedEventAttributes.acceptedRequest.meta.identity }
        assertEquals(setOf("A · relay activity · attempt 1", "A · relay activity · attempt 2"), pollers.toSet())
    }

    @Test
    fun `a permanent failure streams FAILED, compensates, and A still receives every update`() {
        scenarios.put(request.payoutId, ScenarioConfig(Behavior.FAIL_PERMANENT, step = "verifyPayment"))
        env.startStreamedPaymentWorker(scenarios, notifier)

        val items = startAndSubscribe()
        val result = resultOf()

        assertEquals(PaymentOutcome.FAILED, result.outcome)
        assertEquals(PaymentStage.VERIFICATION, result.failedStage)
        // 4 stages passed (8), verification STARTED and FAILED (2), OUTCOME (1).
        assertEquals(11, result.statusUpdatesReceived)
        assertEquals(0, result.gaps)

        val status = items.filter { it.topic == Topics.STATUS }.map { StreamCodec.decode<StageEvent>(it) }
        assertEquals(StageState.FAILED, status[9].state)
        assertEquals(StageEventKind.OUTCOME, status.last().kind)
        // Reversals run in reverse registration order: the ledger entry, then the transfer.
        val reversals = items.filter { it.topic == Topics.PROGRESS }
            .map { StreamCodec.decode<ProgressEvent>(it).detail }
            .filter { it == "writing reversing debit" || it == "recalling instruction" }
        assertEquals(listOf("writing reversing debit", "recalling instruction"), reversals)
        assertEquals("Payment failed - funds returned", describe(workflowId).memo<NotificationMemo>(MemoKeys.NOTIFICATION).message)
        assertBothReplay()
    }

    @Test
    fun `a settlement failure after confirmation corrects the customer notification`() {
        // Every attempt B allows a stage fails, so the retry backoff (about 8s) keeps the failure well behind the
        // confirmation, and A has already sent "Payment confirmed" when it arrives.
        scenarios.put(
            request.payoutId,
            ScenarioConfig(Behavior.FAIL_TRANSIENT, transientFailures = STAGE_MAX_ATTEMPTS, step = "settlePayment"),
        )
        env.startStreamedPaymentWorker(scenarios, notifier)

        val result = run()

        assertEquals(PaymentOutcome.FAILED, result.outcome)
        assertEquals(PaymentStage.SETTLEMENT, result.failedStage)
        assertEquals(
            listOf("Payment confirmed", "Payment failed - funds returned"),
            notifier.callsTo("notify").map { it.message },
        )
        assertEquals("Payment failed - funds returned", describe(workflowId).memo<NotificationMemo>(MemoKeys.NOTIFICATION).message)
    }

    @Test
    fun `with no status updates from the relay, A takes the outcome from B's result and B stops waiting for the ack`() {
        // No retries here, so time skipping can carry B through its 15-minute wait for the ack.
        env.close()
        env = newStreamedPaymentTestEnvironment(timeSkipping = true)
        scenarios.put(request.payoutId, ScenarioConfig(Behavior.FAIL_PERMANENT, step = "verifyPayment"))
        env.startStreamedPaymentWorker(scenarios, notifier, relay = DeadRelay())

        val result = run()

        assertEquals(PaymentOutcome.FAILED, result.outcome)
        assertEquals(0, result.statusUpdatesReceived)
        assertEquals(listOf("Payment failed - funds returned"), notifier.callsTo("notify").map { it.message })

        val processed = client.newUntypedWorkflowStub(processingId).getResult(ProcessPaymentResponse::class.java)
        assertFalse(processed.acknowledged)
        assertEquals(AckState.TIMED_OUT, describe(processingId).memo<StreamMemo>(MemoKeys.STREAM).ackState)
    }

    @Test
    fun `cancelling A cancels B, which reverses what it had done, and A records a FAILED outcome`() {
        cancelDuringLedgering(workflowId)

        assertReversedAndCanceled()
        // A cancel request closes a workflow as CANCELED whatever it returns.
        assertCanceled(workflowId)
        val outcome = describe(workflowId).memo<OutcomeMemo>(MemoKeys.OUTCOME)
        assertEquals(OutcomeMemo(PaymentOutcome.FAILED, PaymentStage.LEDGERING, "cancelled"), outcome)
        assertEquals(listOf("Payment failed - funds returned"), notifier.callsTo("notify").map { it.message })
    }

    @Test
    fun `cancelling B alone ends the payment as FAILED, and A completes`() {
        cancelDuringLedgering(processingId)

        assertReversedAndCanceled()
        val result = resultOf()
        assertEquals(PaymentOutcome.FAILED, result.outcome)
        assertEquals(PaymentStage.LEDGERING, result.failedStage)
        assertEquals("cancelled", result.reason)
        assertEquals(listOf("Payment failed - funds returned"), notifier.callsTo("notify").map { it.message })
    }

    @Test
    fun `cancelling B after it settled leaves A with SETTLED, even when the relay passed nothing on`() {
        cancelDuringAckWait(processingId)

        val result = resultOf()
        assertEquals(PaymentOutcome.SETTLED, result.outcome)
        assertTrue(result.settlementReference.isNotBlank(), "A has B's settlement reference")
        assertEquals(0, result.statusUpdatesReceived)
        assertSettledAndCancelled()
    }

    @Test
    fun `cancelling A after B settled records SETTLED in A's memo`() {
        cancelDuringAckWait(workflowId)

        assertCanceled(workflowId)
        assertEquals(PaymentOutcome.SETTLED, describe(workflowId).memo<OutcomeMemo>(MemoKeys.OUTCOME).outcome)
        assertSettledAndCancelled()
    }

    // ---- helpers ----

    /**
     * The relay gives up at once, so A hears nothing on the stream. [cancelId] is cancelled
     * when B publishes its OUTCOME, which is when B starts waiting for the ack.
     */
    private fun cancelDuringAckWait(cancelId: String) {
        env.startStreamedPaymentWorker(scenarios, notifier, relay = DeadRelay())
        startAndSubscribe { event ->
            if (event.kind == StageEventKind.OUTCOME) client.newUntypedWorkflowStub(cancelId).cancel()
        }
    }

    /** B settled, reversed nothing, and closed as CANCELED; the customer was told it went through. */
    private fun assertSettledAndCancelled() {
        assertCanceled(processingId)
        assertEquals(PaymentOutcome.SETTLED, describe(processingId).memo<OutcomeMemo>(MemoKeys.OUTCOME).outcome)
        val reversals = client.fetchHistory(processingId).events
            .filter { it.hasActivityTaskScheduledEventAttributes() }
            .filter { it.activityTaskScheduledEventAttributes.activityType.name.startsWith("Reverse") }
        assertEquals(emptyList(), reversals)
        assertBTailRanOnCancel()
        assertEquals(listOf("Payment confirmed"), notifier.callsTo("notify").map { it.message })
    }

    /**
     * Ledgering fails transiently on every attempt B allows, which holds B in that stage for about 8s.
     * [cancelId] is cancelled as soon as ledgering starts: after the transfer has moved money,
     * with the ledger reversal already registered.
     */
    private fun cancelDuringLedgering(cancelId: String) {
        scenarios.put(request.payoutId, ScenarioConfig(Behavior.FAIL_TRANSIENT, transientFailures = STAGE_MAX_ATTEMPTS, step = "postToLedger"))
        env.startStreamedPaymentWorker(scenarios, notifier)
        startAndSubscribe { event ->
            if (event.stage == PaymentStage.LEDGERING && event.state == StageState.STARTED) {
                client.newUntypedWorkflowStub(cancelId).cancel()
            }
        }
    }

    /** B closed as CANCELED, after reversing the ledger entry and then the transfer. */
    private fun assertReversedAndCanceled() {
        assertCanceled(processingId)
        val reversals = client.fetchHistory(processingId).events
            .filter { it.hasActivityTaskScheduledEventAttributes() }
            .map { it.activityTaskScheduledEventAttributes.activityType.name }
            .filter { it.startsWith("Reverse") }
        assertEquals(listOf("ReverseLedgerEntry", "ReverseTransfer"), reversals)
        assertBTailRanOnCancel()
    }

    /** A cancelled B still records why it stopped waiting, and answers every poll before closing. */
    private fun assertBTailRanOnCancel() {
        assertEquals(AckState.CANCELLED, describe(processingId).memo<StreamMemo>(MemoKeys.STREAM).ackState)
        val events = client.fetchHistory(processingId).events
        assertEquals(
            events.count { it.hasWorkflowExecutionUpdateAcceptedEventAttributes() },
            events.count { it.hasWorkflowExecutionUpdateCompletedEventAttributes() },
        )
    }

    /** Waits for [id] to close, and checks it closed as CANCELED. */
    private fun assertCanceled(id: String) {
        val closed = assertFailsWith<WorkflowFailedException> {
            client.newUntypedWorkflowStub(id).getResult(Any::class.java)
        }
        assertIs<CanceledFailure>(closed.cause)
    }

    private fun stub() = client.newWorkflowStub(
        PaymentRequestWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).setWorkflowId(workflowId).build(),
    )

    private fun run(): StreamedPaymentResponse = stub().requestPayment(request)

    private fun resultOf(): StreamedPaymentResponse =
        client.newUntypedWorkflowStub(workflowId).getResult(StreamedPaymentResponse::class.java)

    /**
     * Starts A, then reads B's whole stream from offset 0 until the OUTCOME event, passing each
     * status event to [onStatus] as it arrives.
     */
    private fun startAndSubscribe(onStatus: (StageEvent) -> Unit = {}): List<WorkflowStreamItem> {
        WorkflowClient.start(stub()::requestPayment, request)
        starts.await(processingId)
        WorkflowStreamClient.newInstance(client, processingId, StreamCodec.clientOptions()).use { stream ->
            stream.subscribe(SubscribeOptions.getDefaultInstance()).use { subscription ->
                val items = mutableListOf<WorkflowStreamItem>()
                for (item in subscription) {
                    items += item
                    if (item.topic != Topics.STATUS) continue
                    val event = StreamCodec.decode<StageEvent>(item)
                    onStatus(event)
                    if (event.kind == StageEventKind.OUTCOME) break
                }
                return items
            }
        }
    }

    /**
     * Both histories replay against the current code: the stream is rebuilt from workflow code,
     * and its publish signals and poll updates are replayed as recorded. A fresh environment,
     * as in PayoutWorkflowReplayTest: replay starts its own worker.
     */
    private fun assertBothReplay() {
        val replayEnv = newStreamedPaymentTestEnvironment()
        try {
            WorkflowReplayer.replayWorkflowExecution(client.fetchHistory(workflowId), replayEnv, PaymentRequestWorkflowImpl::class.java)
            WorkflowReplayer.replayWorkflowExecution(client.fetchHistory(processingId), replayEnv, PaymentProcessingWorkflowImpl::class.java)
        } finally {
            replayEnv.close()
        }
    }

    private fun describe(id: String): WorkflowExecutionDescription = client.newUntypedWorkflowStub(id).describe()

    private inline fun <reified T> WorkflowExecutionDescription.memo(key: String): T =
        getMemo(key, T::class.java, T::class.java) ?: error("no memo '$key'")

    private fun currentDetails(id: String): String =
        client.newUntypedWorkflowStub(id).query("__temporal_workflow_metadata", WorkflowMetadata::class.java)
            .currentDetails

    private fun StageMemoEntry.key() = listOf(seq, stage, state, outcome)
}
