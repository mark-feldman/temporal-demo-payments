package com.example.payouts.activities

import com.example.payouts.app.StreamCodec
import com.example.payouts.model.activity.*
import com.example.payouts.model.stream.ProgressEvent
import com.example.payouts.model.stream.RetryEvent
import com.example.payouts.model.stream.StageEvent
import com.example.payouts.model.stream.StageEventKind
import com.example.payouts.model.stream.Topics
import com.example.payouts.model.workflow.StageUpdateSignal
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.workflow.PaymentRequestWorkflow
import io.temporal.activity.Activity
import io.temporal.spring.boot.ActivityImpl
import io.temporal.workflowstreams.SubscribeOptions
import io.temporal.workflowstreams.WorkflowStreamClient
import io.temporal.workflowstreams.WorkflowStreamListener
import io.temporal.workflowstreams.WorkflowStreamSubscriptionHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one stage: publishes each step to the `progress` topic of the parent workflow's stream,
 * then returns a reference.
 *
 * Injected failures fire before the last step, so a failed attempt has already published some
 * progress. Those items stay in the stream; attempt 2 and later open with a force-flushed
 * [RetryEvent] so a subscriber can strike them out.
 */
@Component
class StageRunner(private val scenarios: ScenarioStore) {
    private val log = LoggerFactory.getLogger("payouts.activity")

    /** The pause before each step. Tests set it to zero. */
    internal var stepDelay: () -> Duration = { Random.nextLong(400, 900).milliseconds }

    fun run(request: StageRequest, step: String, details: List<String>): StageResponse = runBlocking {
        val attempt = Activity.getExecutionContext().info.attempt
        // close() flushes, including when an injected failure is thrown from inside the block.
        WorkflowStreamClient.fromActivity(StreamCodec.clientOptions()).use { stream ->
            if (attempt > 1) {
                stream.topic(Topics.RETRY).publish(
                    RetryEvent(request.stage, attempt, "attempt ${attempt - 1} failed - retrying"),
                    true,
                )
            }
            details.forEachIndexed { i, detail ->
                if (i == details.lastIndex) scenarios.maybeFail(step, request.payoutId, attempt)
                delay(stepDelay())
                // The first step of an attempt skips the batch interval so the stage visibly starts.
                stream.topic(Topics.PROGRESS)
                    .publish(ProgressEvent(request.stage, attempt, i + 1, details.size, detail), i == 0)
            }
        }
        log.info("[{}] {} (attempt {})", request.payoutId, step, attempt)
        StageResponse(
            stage = request.stage,
            reference = "${request.stage.name.take(3)}-${request.payoutId.substringAfter("po-")}",
            attempt = attempt,
        )
    }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class PaymentStageActivitiesImpl(private val runner: StageRunner) : PaymentStageActivities {
    override fun validatePayment(request: StageRequest) = runner.run(
        request, "validatePayment",
        listOf("checking beneficiary details", "screening sanctions list", "checking payment limits"),
    )

    override fun approvePayment(request: StageRequest) = runner.run(
        request, "approvePayment",
        listOf("scoring risk", "applying approval policy", "recording approval"),
    )

    override fun transferFunds(request: StageRequest) = runner.run(
        request, "transferFunds",
        listOf("debiting source account", "crediting clearing account", "sending instruction to rail"),
    )

    override fun postToLedger(request: StageRequest) = runner.run(
        request, "postToLedger",
        listOf("writing debit entry", "writing credit entry", "balancing journal"),
    )

    override fun verifyPayment(request: StageRequest) = runner.run(
        request, "verifyPayment",
        listOf("matching rail acknowledgement", "reconciling amounts", "checking for duplicates"),
    )

    override fun confirmPayment(request: StageRequest) = runner.run(
        request, "confirmPayment",
        listOf("receiving bank confirmation", "recording confirmation reference", "marking payment confirmed"),
    )

    override fun settlePayment(request: StageRequest) = runner.run(
        request, "settlePayment",
        listOf("netting position", "booking settlement", "closing instruction"),
    )

    override fun reverseTransfer(request: StageRequest) = runner.run(
        request, "reverseTransfer",
        listOf("recalling instruction", "crediting source account", "recording reversal"),
    )

    override fun reverseLedgerEntry(request: StageRequest) = runner.run(
        request, "reverseLedgerEntry",
        listOf("writing reversing debit", "writing reversing credit", "balancing journal"),
    )
}

/**
 * Forwards every item on B's `status` topic to A as an `onStageUpdate` signal.
 *
 * Resumes from the offset in its last heartbeat. The signal is sent before the offset is
 * recorded, so a retry can re-deliver a few items; A drops those by `seq`. How far back it
 * resumes depends on how the attempt ended. One that fails here reports its latest heartbeat
 * with the failure, at most one 2s tick behind. One whose worker dies leaves only what reached
 * the server, and heartbeats are throttled to 80% of the heartbeat timeout, 8s.
 *
 * Heartbeats run on a timer rather than per item: B can go longer than the heartbeat timeout
 * without a status event, during a stage's retry backoff for example.
 */
@Component
@ActivityImpl(taskQueues = ["payouts"])
class StreamRelayActivitiesImpl : StreamRelayActivities {
    private val log = LoggerFactory.getLogger("payouts.relay")

    override fun relayStageUpdates(request: RelayRequest): RelayResponse = runBlocking {
        val context = Activity.getExecutionContext()
        val lastOffset = AtomicLong(context.getHeartbeatDetails(Long::class.javaObjectType).orElse(-1L))
        val relayed = AtomicInteger()
        val sawOutcome = AtomicBoolean()
        val initiator = context.workflowClient
            .newWorkflowStub(PaymentRequestWorkflow::class.java, context.info.workflowId)
        val from = lastOffset.get() + 1
        log.info("relaying {} from offset {} (attempt {})", request.processingWorkflowId, from, context.info.attempt)

        // Signalling is a blocking RPC, so it runs here rather than on the stream's poll executor.
        // One thread, and onNext returns the future: the next item waits for this signal to land.
        val signaller = Executors.newSingleThreadExecutor()
        val handle = AtomicReference<WorkflowStreamSubscriptionHandle>()
        StreamCodec.subscriber(context.workflowClient, request.processingWorkflowId, "A · relay activity · attempt ${context.info.attempt}")
            .use { stream ->
                val options = SubscribeOptions.newBuilder().setTopics(Topics.STATUS).setFromOffset(from).build()
                val subscription = stream.subscribe(options, WorkflowStreamListener { item ->
                    CompletableFuture.runAsync({
                        val event = StreamCodec.decode<StageEvent>(item)
                        initiator.onStageUpdate(StageUpdateSignal(item.offset, event))
                        lastOffset.set(item.offset)
                        relayed.incrementAndGet()
                        if (event.kind == StageEventKind.OUTCOME) {
                            sawOutcome.set(true)
                            // Before this future completes, so the subscription sends no further
                            // poll: B would hold it open until it closes.
                            handle.get()?.close()
                        }
                    }, signaller)
                })
                handle.set(subscription)
                try {
                    while (!sawOutcome.get() && !subscription.doneFuture.isDone) {
                        // Also where a cancellation from A arrives.
                        context.heartbeat(lastOffset.get())
                        // Wakes as soon as the subscription ends, so A is not kept waiting. A
                        // timed-out await cancels the future it awaits, so it awaits a copy.
                        withTimeoutOrNull(HEARTBEAT_EVERY) { subscription.doneFuture.copy().await() }
                    }
                    // A failed poll or signal fails this attempt; the retry resumes from the heartbeat.
                    if (subscription.doneFuture.isCompletedExceptionally) subscription.doneFuture.join()
                } finally {
                    subscription.close()
                    signaller.shutdown()
                }
            }
        RelayResponse(lastOffset = lastOffset.get(), relayed = relayed.get(), sawOutcome = sawOutcome.get())
    }

    private companion object {
        val HEARTBEAT_EVERY = 2.seconds
    }
}
