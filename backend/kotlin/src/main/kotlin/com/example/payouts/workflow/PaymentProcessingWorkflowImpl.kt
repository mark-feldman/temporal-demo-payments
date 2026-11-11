package com.example.payouts.workflow

import com.example.payouts.activities.PaymentStageActivities
import com.example.payouts.app.StreamCodec
import com.example.payouts.model.activity.StageRequest
import com.example.payouts.model.activity.StageResponse
import com.example.payouts.model.domain.PaymentOutcome
import com.example.payouts.model.domain.PaymentStage
import com.example.payouts.model.domain.StageState
import com.example.payouts.model.memo.*
import com.example.payouts.model.stream.StageEvent
import com.example.payouts.model.stream.StageEventKind
import com.example.payouts.model.stream.Topics
import com.example.payouts.model.workflow.ProcessPaymentRequest
import com.example.payouts.model.workflow.ProcessPaymentResponse
import com.example.payouts.model.workflow.ProcessingCancelledSignal
import com.example.payouts.model.workflow.StatusAcknowledgement
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.common.SearchAttributeKey
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.CanceledFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.Saga
import io.temporal.workflow.TimerOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInit
import io.temporal.workflowstreams.WorkflowStream
import java.time.Duration

/**
 * The attempt cap on each of B's stages. A failure-injection count at or above it exhausts the
 * stage; `DemoScenarioDefaultsTest` holds scenario 6's counts below it.
 */
const val STAGE_MAX_ATTEMPTS = 5

/**
 * Workflow B: runs the seven stages and hosts the Workflow Stream that reports them.
 *
 * Every transition goes through [publishState], which publishes the status event, upserts the
 * memo and sets current details in one place, so the three never disagree.
 *
 * The stream is created in the @WorkflowInit constructor, which runs before any handler is
 * dispatched, so a subscriber that attaches immediately is accepted rather than rejected.
 */
@WorkflowImpl(taskQueues = [TASK_QUEUE])
class PaymentProcessingWorkflowImpl @WorkflowInit constructor(
    @Suppress("UNUSED_PARAMETER") request: ProcessPaymentRequest,
) : PaymentProcessingWorkflow {

    private val log = Workflow.getLogger(javaClass)

    /** No continue-as-new, so there is no prior state to carry and the input stays @Serializable. */
    private val workflowStream = WorkflowStream.newInstance(null, StreamCodec.workflowOptions())
    private val status = workflowStream.topic(Topics.STATUS)

    private var seq = 0
    private var acknowledged = false
    private var stream = StreamMemo(topics = Topics.ALL)
    private val stages = mutableListOf<StageMemoEntry>()

    private val stageActivities = Workflow.newActivityStub(
        PaymentStageActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(15))
            setScheduleToCloseTimeout(Duration.ofMinutes(2))
            setTaskQueue(TASK_QUEUE)
            setRetryOptions {
                setInitialInterval(Duration.ofSeconds(1))
                setBackoffCoefficient(1.5)
                setMaximumInterval(Duration.ofSeconds(5))
                setMaximumAttempts(STAGE_MAX_ATTEMPTS)
            }
        },
    )

    /** Compensation has no attempt cap. */
    private val reversals = Workflow.newActivityStub(
        PaymentStageActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(15))
            setScheduleToCloseTimeout(Duration.ofHours(1))
            setTaskQueue(TASK_QUEUE)
            setRetryOptions {
                setInitialInterval(Duration.ofSeconds(2))
                setBackoffCoefficient(1.0)
            }
        },
    )

    override fun processPayment(request: ProcessPaymentRequest): ProcessPaymentResponse {
        Workflow.upsertTypedSearchAttributes(
            PAYOUT_ID.valueSet(request.payoutId),
            SCENARIO_NAME.valueSet(SCENARIO),
        )
        val saga = Saga { setContinueWithError(true) }
        var current = PaymentStage.VALIDATION
        var settlementReference = ""

        val outcome = try {
            for (stage in PaymentStage.entries) {
                current = stage
                publishState(StageEventKind.STAGE, stage, StageState.STARTED, detail = "${stage.label} started")
                // Registered before the call it undoes, so it runs even if the call moved money
                // and then failed.
                compensationFor(stage)?.let { reversal ->
                    saga.addCompensation { reversal(StageRequest(request.payoutId, stage, request.amount)) }
                }
                val result = run(stage, StageRequest(request.payoutId, stage, request.amount))
                if (stage == PaymentStage.SETTLEMENT) settlementReference = result.reference
                publishState(
                    StageEventKind.STAGE, stage, StageState.PASSED,
                    detail = "${stage.label} passed (attempt ${result.attempt}, ${result.reference})",
                )
            }
            OutcomeMemo(PaymentOutcome.SETTLED, settlementReference = settlementReference)
        } catch (e: ActivityFailure) {
            // A cancel, from A or on B directly, fails the running stage the same way. B still
            // compensates and publishes its outcome, then closes as CANCELED.
            val reason = when (val cause = e.cause) {
                is CanceledFailure -> "cancelled"
                is ApplicationFailure -> cause.originalMessage
                else -> e.message.orEmpty()
            }
            publishState(StageEventKind.STAGE, current, StageState.FAILED, detail = reason)
            // Compensation must not be cancelled along with whatever failed.
            Workflow.newDetachedCancellationScope { saga.compensate() }.run()
            OutcomeMemo(PaymentOutcome.FAILED, failedStage = current, reason = reason)
        }

        publishState(StageEventKind.OUTCOME, outcome = outcome.outcome, detail = outcome.reason.ifBlank { "settled" })
        Workflow.upsertMemo(mapOf(MemoKeys.OUTCOME to outcome))
        val finalSeq = seq - 1

        // Stay open until A has every status update, so a relay that is retrying can still read
        // the log. The log is gone once this workflow closes. A cancelled B does not wait.
        val ackState = if (CancellationScope.current().isCancelRequested) {
            AckState.CANCELLED
        } else {
            stream = stream.copy(finalSeq = finalSeq, ackState = AckState.AWAITING)
            Workflow.upsertMemo(mapOf(MemoKeys.STREAM to stream))
            Workflow.setCurrentDetails(details(headline = "**${outcome.outcome}** - awaiting A's acknowledgement"))
            awaitAcknowledgement()
        }
        stream = stream.copy(finalSeq = finalSeq, ackState = ackState)
        Workflow.upsertMemo(mapOf(MemoKeys.STREAM to stream))
        val ackHeadline = when (ackState) {
            AckState.RECEIVED -> "acknowledged by A"
            AckState.CANCELLED -> "cancelled"
            else -> "no acknowledgement from A"
        }
        Workflow.setCurrentDetails(details(headline = "**${outcome.outcome}** - $ackHeadline"))
        log.info("{} {} (ack {})", request.payoutId, outcome.outcome, ackState)

        // Polls still waiting, such as an open page's, would be abandoned when B closes. This
        // releases them, with any items they have not had yet, and rejects new ones, so B closes
        // with no update handler running. Detached: in a cancelled B the await would throw at once.
        Workflow.newDetachedCancellationScope {
            if (ackState == AckState.CANCELLED) sendOutcomeToA(request.parentWorkflowId, outcome)
            workflowStream.detachPollers()
            Workflow.await { Workflow.isEveryHandlerFinished() }
        }.run()

        return ProcessPaymentResponse(
            payoutId = request.payoutId,
            outcome = outcome.outcome,
            failedStage = outcome.failedStage,
            reason = outcome.reason,
            settlementReference = outcome.settlementReference,
            finalSeq = finalSeq,
            acknowledged = ackState == AckState.RECEIVED,
        )
    }

    /**
     * A cancel request closes B as CANCELED whatever it returns, so A gets no result from it.
     * The signal lands in A's history before B closes, so A has it by the time it sees the
     * cancellation.
     */
    private fun sendOutcomeToA(parentWorkflowId: String, outcome: OutcomeMemo) {
        try {
            Workflow.newExternalWorkflowStub(PaymentRequestWorkflow::class.java, parentWorkflowId).onProcessingCancelled(
                ProcessingCancelledSignal(outcome.outcome, outcome.failedStage, outcome.reason, outcome.settlementReference),
            )
        } catch (e: ApplicationFailure) {
            log.warn("outcome not delivered to {}: {}", parentWorkflowId, e.message)
        }
    }

    override fun statusAcknowledged(ack: StatusAcknowledgement) {
        acknowledged = true
    }

    /**
     * An explicit timer in a cancellation scope, as in PayoutWorkflowImpl, cancelled once A
     * acknowledges, so history records TimerCanceled. Before SDK 1.40, await's built-in timeout
     * left its timer running when the condition won (SdkFlag CANCEL_AWAIT_TIMER_ON_CONDITION is
     * enabled from 1.40); the explicit timer does not depend on that, and carries a summary.
     *
     * A cancel during the wait ends it: await throws, and the deadline scope is cancelled with B.
     */
    private fun awaitAcknowledgement(): AckState {
        var deadlinePassed = false
        val deadline = Workflow.newCancellationScope(Runnable {
            Workflow.newTimer(ACK_WAIT, TimerOptions.newBuilder().setSummary("Waiting for A's acknowledgement").build())
                .thenApply { deadlinePassed = true }
        })
        deadline.run()
        try {
            Workflow.await { acknowledged || deadlinePassed }
        } catch (e: CanceledFailure) {
            return AckState.CANCELLED
        }
        if (acknowledged) deadline.cancel("A acknowledged")
        return if (acknowledged) AckState.RECEIVED else AckState.TIMED_OUT
    }

    private fun run(stage: PaymentStage, request: StageRequest): StageResponse = when (stage) {
        PaymentStage.VALIDATION -> stageActivities.validatePayment(request)
        PaymentStage.APPROVAL -> stageActivities.approvePayment(request)
        PaymentStage.FUNDS_TRANSFER -> stageActivities.transferFunds(request)
        PaymentStage.LEDGERING -> stageActivities.postToLedger(request)
        PaymentStage.VERIFICATION -> stageActivities.verifyPayment(request)
        PaymentStage.CONFIRMATION -> stageActivities.confirmPayment(request)
        PaymentStage.SETTLEMENT -> stageActivities.settlePayment(request)
    }

    /** The stages that move money or write the ledger have something to undo. */
    private fun compensationFor(stage: PaymentStage): ((StageRequest) -> Unit)? = when (stage) {
        PaymentStage.FUNDS_TRANSFER -> { r -> reversals.reverseTransfer(r) }
        PaymentStage.LEDGERING -> { r -> reversals.reverseLedgerEntry(r) }
        PaymentStage.VALIDATION, PaymentStage.APPROVAL, PaymentStage.VERIFICATION,
        PaymentStage.CONFIRMATION, PaymentStage.SETTLEMENT -> null
    }

    /**
     * One transition: the status event on the stream, the memo, and current details.
     * Publishing from workflow code adds no history event; the memo upsert adds one.
     */
    private fun publishState(
        kind: StageEventKind,
        stage: PaymentStage? = null,
        state: StageState? = null,
        outcome: PaymentOutcome? = null,
        detail: String,
    ) {
        val now = Workflow.currentTimeMillis()
        val event = StageEvent(seq, kind, stage, state, outcome, detail, now)
        status.publish(event)
        seq += 1
        stages += StageMemoEntry(event.seq, stage, state, outcome, now)
        stream = stream.copy(statusEventsPublished = seq)
        Workflow.upsertMemo(
            mapOf(
                MemoKeys.PROGRESS to ProgressMemo(stage, state, outcome, event.seq, now),
                MemoKeys.STAGES to StagesMemo(stages.toList()),
                MemoKeys.STREAM to stream,
            ),
        )
        val headline = when (kind) {
            StageEventKind.STAGE -> "**${stage?.label?.uppercase()} - stage ${stage?.number}/${PaymentStage.COUNT}** ($state)"
            StageEventKind.OUTCOME -> "**$outcome**"
        }
        Workflow.setCurrentDetails(details(headline))
    }

    private fun details(headline: String): String {
        val latest = stages.filter { it.stage != null }.associateBy { it.stage }
        val checklist = PaymentStage.entries.joinToString(" · ") { stage ->
            val mark = when (latest[stage]?.state) {
                StageState.PASSED -> "✓"
                StageState.STARTED -> "◐"
                StageState.FAILED -> "✗"
                null -> "○"
            }
            "$mark ${stage.label}"
        }
        val ack = when (stream.ackState) {
            AckState.NOT_YET -> ""
            AckState.AWAITING -> "\n\nWaiting for A to acknowledge seq 0-${stream.finalSeq}"
            AckState.RECEIVED -> "\n\nA acknowledged seq 0-${stream.finalSeq}"
            AckState.TIMED_OUT -> "\n\nA did not acknowledge within ${ACK_WAIT.toMinutes()} minutes"
            AckState.CANCELLED -> "\n\nCancelled before A acknowledged"
        }
        return "$headline\n\n$checklist\n\nStream: ${stream.statusEventsPublished} status events published · " +
            "topics ${stream.topics.joinToString { "`$it`" }}$ack"
    }

    private companion object {
        const val SCENARIO = "streamed"

        /** Longer than the relay's schedule-to-close, so a relay still retrying always finds the log. */
        val ACK_WAIT: Duration = Duration.ofMinutes(15)

        val PAYOUT_ID = SearchAttributeKey.forKeyword("payoutId")
        val SCENARIO_NAME = SearchAttributeKey.forKeyword("scenarioName")
    }
}
