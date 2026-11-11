package com.example.payouts.workflow

import com.example.payouts.model.workflow.*
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

/**
 * Workflow A in scenario 6: initiates the payment, starts [PaymentProcessingWorkflow] as a
 * child, and receives the child's status updates as signals from the relay activity.
 */
@WorkflowInterface
interface PaymentRequestWorkflow {
    @WorkflowMethod
    fun requestPayment(request: StartStreamedPaymentRequest): StreamedPaymentResponse

    /** One per status event on B's stream. Re-deliveries after a relay retry are dropped by seq. */
    @SignalMethod
    fun onStageUpdate(update: StageUpdateSignal)

    /** Sent by B when it is cancelled: a cancelled workflow closes with no result to return. */
    @SignalMethod
    fun onProcessingCancelled(outcome: ProcessingCancelledSignal)
}

/**
 * Workflow B in scenario 6: runs the seven payment stages and hosts the Workflow Stream that
 * reports them.
 */
@WorkflowInterface
interface PaymentProcessingWorkflow {
    @WorkflowMethod
    fun processPayment(request: ProcessPaymentRequest): ProcessPaymentResponse

    /** A has every status update. B waits for this before it returns, so its log stays readable. */
    @SignalMethod
    fun statusAcknowledged(ack: StatusAcknowledgement)
}

private const val PROCESSING_SUFFIX = "-processing"

/** B's workflow id, derived from A's so the browser and the relay can address it. */
fun processingWorkflowIdFor(requestWorkflowId: String) = "$requestWorkflowId$PROCESSING_SUFFIX"

/** A's workflow id, from B's. */
fun requestWorkflowIdFor(processingWorkflowId: String) = processingWorkflowId.removeSuffix(PROCESSING_SUFFIX)
