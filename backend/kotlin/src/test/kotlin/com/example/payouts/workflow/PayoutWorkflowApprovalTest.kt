package com.example.payouts.workflow

import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.workflow.ApprovalDecisionRequest
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.payoutRequest
import io.temporal.api.enums.v1.EventType
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The human-approval branch: a durable wait on a signal, with a workflow timer as the
 * deadline. Everything here runs on the test server's clock -- the 30s deadline is skipped,
 * not waited out.
 */
class PayoutWorkflowApprovalTest : PayoutWorkflowTestBase() {

    @Test
    fun `an amount over the senior threshold parks on a signal`() {
        startWorker()
        val stub = newStub("approval-senior")
        // $2,500 -- above the $1,000 senior threshold.
        start(stub, payoutRequest(amountMinor = 250_000))

        val parked = awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)
        assertEquals(ApprovalTier.SENIOR, parked.approvalTier)
        assertEquals("Waiting for SENIOR approval", parked.currentStep)
        // It parked before touching the rail: nothing has been submitted to the bank.
        assertTrue(activities.callsTo("submitToRail").isEmpty())

        stub.approve(ApprovalDecisionRequest(approved = true, approver = "ops-lead"))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))

        assertEquals(BusinessStatus.COMPLETED, resultOf(stub).status)
        assertContains(statesOf(stub), "APPROVED")
    }

    @Test
    fun `an amount in the L1 band also parks`() {
        startWorker()
        val stub = newStub("approval-l1")
        // $600 -- between the $500 and $1,000 thresholds.
        start(stub, payoutRequest(amountMinor = 60_000))

        assertEquals(ApprovalTier.L1, awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL).approvalTier)
    }

    @Test
    fun `approval records the approver and lets the payout through`() {
        startWorker()
        val stub = newStub("approval-approved")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)

        stub.approve(ApprovalDecisionRequest(approved = true, approver = "dana"))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        assertContains(stub.currentStatus().history, "APPROVED: Approved by dana")
    }

    @Test
    fun `a declined approval cancels the payout and releases the reservation`() {
        startWorker()
        val stub = newStub("approval-declined")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)

        stub.approve(ApprovalDecisionRequest(approved = false, approver = "dana", note = "beneficiary unknown"))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.CANCELLED, result.status)
        // A decline has its own failure category, distinct from a validation failure.
        assertEquals(FailureCategory.APPROVAL_DECLINED, result.failureCategory)

        assertEquals(1, activities.callsTo("releaseReservedFunds").size, "the reservation is unwound")
        assertTrue(
            activities.callsTo("reverseRailInstruction").isEmpty(),
            "nothing reached the bank, so there is nothing to reverse",
        )
        assertContains(statesOf(stub), "COMPENSATED")
    }

    @Test
    fun `no decision before the deadline cancels the payout as APPROVAL_TIMEOUT`() {
        startWorker()
        val stub = newStub("approval-timeout")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)

        // Blocking on the result unlocks time skipping, so the 30s timer fires immediately
        // in virtual time rather than costing the suite 30 real seconds.
        val result = resultOf(stub)

        assertEquals(BusinessStatus.CANCELLED, result.status)
        assertEquals(FailureCategory.APPROVAL_TIMEOUT, result.failureCategory)
        assertEquals(1, activities.callsTo("releaseReservedFunds").size)
    }

    @Test
    fun `an approval arriving after the deadline cannot rescue the payout`() {
        startWorker()
        val stub = newStub("approval-late-signal")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)

        // +60s is past the 30s deadline on the test server's own clock. By then the workflow
        // has already taken the timeout branch, so the signal either hits the
        // `approvalDeadlinePassed` guard or lands on a closed execution -- both mean the same
        // thing: a late approval does not flip the outcome.
        env.registerDelayedCallback(Duration.ofSeconds(60)) {
            runCatching { stub.approve(ApprovalDecisionRequest(approved = true, approver = "too-late")) }
        }

        val result = resultOf(stub)
        assertEquals(BusinessStatus.CANCELLED, result.status)
        assertEquals(FailureCategory.APPROVAL_TIMEOUT, result.failureCategory)
    }

    @Test
    fun `an approval arriving before the deadline wins`() {
        startWorker()
        val stub = newStub("approval-in-time")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)

        // +25s, inside the 30s deadline. Also delivers the bank callback so the run finishes.
        env.registerDelayedCallback(Duration.ofSeconds(25)) {
            runCatching { stub.approve(ApprovalDecisionRequest(approved = true, approver = "just-in-time")) }
        }
        env.registerDelayedCallback(Duration.ofSeconds(40)) {
            runCatching { stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED)) }
        }

        val result = resultOf(stub)
        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertEquals(FailureCategory.NONE, result.failureCategory)
    }

    @Test
    fun `the approval deadline timer is cancelled once the decision arrives`() {
        startWorker()
        val stub = newStub("approval-timer-cancelled")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)
        stub.approve(ApprovalDecisionRequest(approved = true, approver = "dana"))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        // `await(timeout, cond)` cancels its own internal timer; a bare `Workflow.newTimer`
        // does not. Both deadlines here are bare timers owned by a cancellation scope, so
        // every TimerStarted must be resolved -- an unresolved one is a leak left in history.
        val events = client.fetchHistory("approval-timer-cancelled").history.eventsList
        val started = events.count { it.eventType == EventType.EVENT_TYPE_TIMER_STARTED }
        val resolved = events.count {
            it.eventType == EventType.EVENT_TYPE_TIMER_CANCELED ||
                it.eventType == EventType.EVENT_TYPE_TIMER_FIRED
        }
        assertTrue(started > 0, "the approval and bank waits each start a timer")
        assertEquals(started, resolved, "every timer must be cancelled or fired, never left dangling")
    }
}
