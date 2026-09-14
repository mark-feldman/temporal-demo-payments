package com.example.payouts.workflow

import com.example.payouts.model.workflow.*
import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface PayoutWorkflow {
    @WorkflowMethod
    fun processPayout(request: ProcessPayoutRequest): ProcessPayoutResponse

    /**
     * Signals are fire-and-forget with no return value, so the UI cannot tell "accepted"
     * from "arrived after the timer fired" from the signal alone. It polls the Query below
     * until businessStatus leaves AWAITING_APPROVAL and reports accordingly.
     */
    @SignalMethod
    fun approve(request: ApprovalDecisionRequest)

    /** Genuinely fire-and-forget: this is a bank callback. */
    @SignalMethod
    fun bankStatusUpdate(request: BankStatusUpdateRequest)

    @QueryMethod
    fun currentStatus(): PayoutStatusResponse
}

const val TASK_QUEUE = "payouts"
