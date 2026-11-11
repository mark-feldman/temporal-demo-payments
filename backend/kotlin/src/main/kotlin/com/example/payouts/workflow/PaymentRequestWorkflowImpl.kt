package com.example.payouts.workflow

import com.example.payouts.activities.NotificationActivities
import com.example.payouts.activities.StreamRelayActivities
import com.example.payouts.model.activity.NotifyRequest
import com.example.payouts.model.activity.RelayRequest
import com.example.payouts.model.activity.RelayResponse
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.PaymentOutcome
import com.example.payouts.model.domain.PaymentStage
import com.example.payouts.model.domain.StageState
import com.example.payouts.model.memo.*
import com.example.payouts.model.stream.StageEventKind
import com.example.payouts.model.workflow.*
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.api.enums.v1.ParentClosePolicy
import io.temporal.common.SearchAttributeKey
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.CanceledFailure
import io.temporal.failure.ChildWorkflowFailure
import io.temporal.failure.TemporalFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Async
import io.temporal.workflow.CancellationScope
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Workflow A: starts the processing workflow as a child and follows it through the status
 * updates the relay activity signals in.
 *
 * Each update is appended, never overwritten, and checked against the next expected `seq`. A
 * acknowledges B only once it holds seq 0 to the OUTCOME's seq with no gaps.
 *
 * A's result comes from the child's result. The signals are A's live view; they do not decide
 * the outcome.
 */
@WorkflowImpl(taskQueues = [TASK_QUEUE])
class PaymentRequestWorkflowImpl : PaymentRequestWorkflow {

    private val log = Workflow.getLogger(javaClass)

    // ---- state built from the signals ----
    private val accepted = mutableListOf<StageMemoEntry>()
    private var nextSeq = 0
    private var finalSeq: Int? = null
    private var relay = RelayMemo()
    private var progress = ProgressMemo()
    private var notification: NotificationMemo? = null
    private var cancelledOutcome: ProcessingCancelledSignal? = null

    /**
     * Long-running and heartbeating: lives as long as B does.
     *
     *  - Heartbeat timeout 10s: a dead worker is noticed in 10s, not after start-to-close.
     *    Sends are throttled to 8s (80% of it).
     *  - Start-to-close 5m per attempt, well above B's run. If hit, the next attempt resumes.
     *  - Schedule-to-close 10m caps all attempts. B waits longer than that for the ack.
     */
    private val relayActivities = Workflow.newActivityStub(
        StreamRelayActivities::class.java,
        ActivityOptions {
            setHeartbeatTimeout(Duration.ofSeconds(10))
            setStartToCloseTimeout(Duration.ofMinutes(5))
            setScheduleToCloseTimeout(Duration.ofMinutes(10))
            setTaskQueue(TASK_QUEUE)
            setSummary("Relay B's status stream to A")
            setRetryOptions {
                setInitialInterval(Duration.ofSeconds(1))
                setBackoffCoefficient(2.0)
                setMaximumInterval(Duration.ofSeconds(10))
            }
        },
    )

    private val notifier = Workflow.newActivityStub(
        NotificationActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(2))
            setTaskQueue(TASK_QUEUE)
            setSummary("Notify customer")
        },
    )

    override fun requestPayment(request: StartStreamedPaymentRequest): StreamedPaymentResponse {
        val workflowId = Workflow.getInfo().workflowId
        upsertSearchAttributes(request)

        val childId = processingWorkflowIdFor(workflowId)
        val child = Workflow.newChildWorkflowStub(
            PaymentProcessingWorkflow::class.java,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId(childId)
                .setTaskQueue(TASK_QUEUE)
                // Terminating A cancels B, which reverses what it had done, rather than
                // terminating it mid-payment as the default policy would.
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                .setStaticSummary("Process payout ${request.payoutId} - hosts the status stream")
                .setStaticDetails(
                    "${request.amount} over the ${request.rail} rail in ${request.region}.\n\n" +
                        "Started by `$workflowId`, which follows it through its Workflow Stream.",
                )
                .setMemo(
                    mapOf(
                        MemoKeys.PAYMENT to PaymentMemo(
                            payoutId = request.payoutId,
                            amount = request.amount,
                            rail = request.rail,
                            region = request.region,
                            parentWorkflowId = workflowId,
                        ),
                    ),
                )
                .build(),
        )
        val processed = Async.function(
            child::processPayment,
            ProcessPaymentRequest(request.payoutId, request.amount, request.rail, request.region, workflowId),
        )

        // B exists, and its stream with it, before the relay subscribes.
        val execution = Workflow.getWorkflowExecution(child).get()
        relay = relay.copy(childWorkflowId = childId, childRunId = execution.runId)
        publishState()
        val relayed = Async.function(relayActivities::relayStageUpdates, RelayRequest(childId))

        try {
            Workflow.await { confirmedOrFailed() || processed.isCompleted }
            // Once B has returned, its result decides: a relay that gave up never passed on the outcome.
            val failed = if (processed.isCompleted) childResult(request, processed).outcome != PaymentOutcome.SETTLED else streamedFailure()
            notifyCustomer(request, failed)

            Workflow.await { hasEveryUpdate() || processed.isCompleted }
            if (hasEveryUpdate() && !processed.isCompleted) acknowledge(child)
        } catch (e: TemporalFailure) {
            // Whatever was running when A was cancelled fails with it: an await, the notify
            // activity, the ack signal. The cancel reaches B too, as A's child; B reverses what it
            // had done and returns FAILED.
            if (!CancellationScope.current().isCancelRequested) throw e
            log.info("cancelled; waiting for {} to finish", childId)
        }

        // Outside the cancelled scope, so a cancelled A still waits for B and records the outcome.
        // A cancel request closes A as CANCELED whatever this returns; the memo keeps the outcome.
        lateinit var response: StreamedPaymentResponse
        Workflow.newDetachedCancellationScope { response = finish(request, childResult(request, processed), relayed) }.run()
        return response
    }

    /**
     * B's result. A cancel request closes a workflow as CANCELED whatever it returns, so a
     * cancelled B has none; it signals its outcome instead, before it closes. A cancel before
     * the OUTCOME is a FAILED payment that B reversed; one during the wait for the ack leaves
     * the outcome B had reached, SETTLED included. Without the signal, the payment is FAILED.
     */
    private fun childResult(
        request: StartStreamedPaymentRequest,
        processed: Promise<ProcessPaymentResponse>,
    ): ProcessPaymentResponse = try {
        processed.get()
    } catch (e: ChildWorkflowFailure) {
        if (e.cause !is CanceledFailure) throw e
        val sent = cancelledOutcome
        ProcessPaymentResponse(
            payoutId = request.payoutId,
            outcome = sent?.outcome ?: PaymentOutcome.FAILED,
            failedStage = if (sent != null) sent.failedStage else accepted.lastOrNull { it.stage != null }?.stage,
            reason = sent?.reason ?: "cancelled",
            settlementReference = sent?.settlementReference.orEmpty(),
            finalSeq = finalSeq ?: -1,
            acknowledged = false,
        )
    }

    private fun finish(
        request: StartStreamedPaymentRequest,
        result: ProcessPaymentResponse,
        relayed: Promise<RelayResponse>,
    ): StreamedPaymentResponse {
        // The first notice went out at confirmation. If settlement then failed, B reversed the
        // payment, and this corrects it; otherwise the message is unchanged and nothing is sent.
        notifyCustomer(request, failed = result.outcome != PaymentOutcome.SETTLED)
        // Normally already done: it returns once it has signalled the OUTCOME event. Only an
        // activity failure is caught; anything else, such as the SDK destroying this workflow's
        // thread on eviction, has to propagate.
        try {
            relayed.get()
        } catch (e: ActivityFailure) {
            log.warn("relay ended with {}", e.message)
        }

        val outcome = OutcomeMemo(result.outcome, result.failedStage, result.reason, result.settlementReference)
        Workflow.upsertMemo(mapOf(MemoKeys.OUTCOME to outcome))
        val terminal = if (result.outcome == PaymentOutcome.SETTLED) BusinessStatus.COMPLETED else BusinessStatus.FAILED
        Workflow.upsertTypedSearchAttributes(BUSINESS_STATUS.valueSet(terminal.name))
        publishState(headline = "**${result.outcome}** - ${result.reason.ifBlank { result.settlementReference }}")

        return StreamedPaymentResponse(
            payoutId = request.payoutId,
            outcome = result.outcome,
            failedStage = result.failedStage,
            reason = result.reason,
            settlementReference = result.settlementReference,
            statusUpdatesReceived = relay.received,
            gaps = relay.gaps,
        )
    }

    /** B can close between the check and the signal, if it was cancelled; the signal then fails. */
    private fun acknowledge(child: PaymentProcessingWorkflow) {
        try {
            child.statusAcknowledged(StatusAcknowledgement(finalSeq ?: -1))
        } catch (e: ApplicationFailure) {
            log.warn("acknowledgement not delivered: {}", e.message)
            return
        }
        relay = relay.copy(ackSent = true)
        publishState()
    }

    /**
     * Runs once per signal, in the order the signals were recorded. Never overwrites: an update
     * is appended, dropped as a duplicate, or appended and counted as a gap.
     */
    override fun onStageUpdate(update: StageUpdateSignal) {
        val event = update.event
        if (event.seq < nextSeq) {
            // A relay retry resumed from an older heartbeat offset.
            relay = relay.copy(duplicatesDropped = relay.duplicatesDropped + 1)
            publishState()
            return
        }
        val gap = event.seq - nextSeq
        accepted += StageMemoEntry(event.seq, event.stage, event.state, event.outcome, Workflow.currentTimeMillis())
        nextSeq = event.seq + 1
        if (event.kind == StageEventKind.OUTCOME) finalSeq = event.seq
        relay = relay.copy(
            received = relay.received + 1,
            gaps = relay.gaps + gap,
            lastOffset = update.offset,
        )
        progress = ProgressMemo(event.stage, event.state, event.outcome, event.seq, Workflow.currentTimeMillis())
        publishState()
    }

    override fun onProcessingCancelled(outcome: ProcessingCancelledSignal) {
        cancelledOutcome = outcome
    }

    private fun confirmedOrFailed() =
        finalSeq != null ||
            accepted.any { it.stage == PaymentStage.CONFIRMATION && it.state == StageState.PASSED }

    /** Every seq from 0 to the OUTCOME's, once each. */
    private fun hasEveryUpdate(): Boolean {
        val last = finalSeq ?: return false
        return relay.gaps == 0 && relay.received == last + 1
    }

    private fun streamedFailure() =
        accepted.any { it.outcome == PaymentOutcome.FAILED || it.state == StageState.FAILED }

    /** Sends only when the message differs from the last one sent. */
    private fun notifyCustomer(request: StartStreamedPaymentRequest, failed: Boolean) {
        val message = if (failed) "Payment failed - funds returned" else "Payment confirmed"
        if (notification?.message == message) return
        notifier.notify(NotifyRequest(request.payoutId, "customer", message))
        notification = NotificationMemo(customerNotified = true, message = message, at = Workflow.currentTimeMillis())
        publishState()
    }

    /** Memo and current details, together. */
    private fun publishState(headline: String? = null) {
        Workflow.upsertMemo(
            buildMap {
                put(MemoKeys.PROGRESS, progress)
                put(MemoKeys.STAGES, StagesMemo(accepted.toList()))
                put(MemoKeys.RELAY, relay)
                notification?.let { put(MemoKeys.NOTIFICATION, it) }
            },
        )
        val top = headline ?: when {
            progress.outcome != null -> "**${progress.outcome}** - received from `${relay.childWorkflowId}`"
            progress.stage != null ->
                "**Watching `${relay.childWorkflowId}` - stage ${progress.stage?.number}/${PaymentStage.COUNT}, " +
                    "${progress.stage?.label} ${progress.state}**"
            else -> "**Watching `${relay.childWorkflowId}`** - no updates yet"
        }
        val range = if (relay.received == 0) "" else " (seq 0-${nextSeq - 1})"
        val ack = if (relay.ackSent) " · acknowledged to B" else ""
        Workflow.setCurrentDetails(
            "$top\n\nReceived ${relay.received} status updates$range · " +
                "${relay.duplicatesDropped} duplicates dropped · ${relay.gaps} gaps$ack\n\n" +
                "Customer notified: ${notification?.message ?: "no"}",
        )
    }

    private fun upsertSearchAttributes(request: StartStreamedPaymentRequest) {
        Workflow.upsertTypedSearchAttributes(
            PAYOUT_ID.valueSet(request.payoutId),
            CUSTOMER_ID.valueSet(request.customerId),
            RAIL.valueSet(request.rail.name),
            REGION.valueSet(request.region.name),
            AMOUNT_MINOR.valueSet(request.amount.amountMinor),
            CURRENCY.valueSet(request.amount.currency),
            SCENARIO_NAME.valueSet(request.scenarioName),
            BUSINESS_STATUS.valueSet(BusinessStatus.RECEIVED.name),
        )
    }

    private companion object {
        val PAYOUT_ID = SearchAttributeKey.forKeyword("payoutId")
        val CUSTOMER_ID = SearchAttributeKey.forKeyword("customerId")
        val RAIL = SearchAttributeKey.forKeyword("rail")
        val REGION = SearchAttributeKey.forKeyword("region")
        val AMOUNT_MINOR = SearchAttributeKey.forLong("amountMinor")
        val CURRENCY = SearchAttributeKey.forKeyword("currency")
        val BUSINESS_STATUS = SearchAttributeKey.forKeyword("businessStatus")
        val SCENARIO_NAME = SearchAttributeKey.forKeyword("scenarioName")
    }
}
