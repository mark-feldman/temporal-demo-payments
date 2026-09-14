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
     * Signals are fire-and-forget and return nothing, so the caller reads the Query below
     * until businessStatus leaves AWAITING_APPROVAL to see the outcome.
     */
    @SignalMethod
    fun approve(request: ApprovalDecisionRequest)

    /** A bank callback: fire-and-forget. */
    @SignalMethod
    fun bankStatusUpdate(request: BankStatusUpdateRequest)

    @QueryMethod
    fun currentStatus(): PayoutStatusResponse
}

const val TASK_QUEUE = "payouts"
