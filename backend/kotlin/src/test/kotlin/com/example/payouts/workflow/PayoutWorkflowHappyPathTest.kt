package com.example.payouts.workflow

import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.payoutRequest
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The baseline the other scenarios diverge from: validate, reserve, FX, select rail, submit,
 * confirm by callback, complete.
 */
class PayoutWorkflowHappyPathTest : PayoutWorkflowTestBase() {

    @Test
    fun `small payout completes without ever asking for approval`() {
        startWorker()
        val stub = newStub("happy-path")
        // $250 -- below the $500 L1 threshold, so the approval branch must not be taken.
        start(stub, payoutRequest(amountMinor = 25_000))

        val parked = awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        assertEquals(ApprovalTier.NONE, parked.approvalTier)
        assertEquals("BANK-HTTP-000001", parked.bankReference)
        assertEquals(25_000, parked.usdEquivalentMinor, "USD converts 1:1 via the canned rate table")

        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertEquals(FailureCategory.NONE, result.failureCategory)
        assertEquals("completed", result.message)
        assertEquals("BANK-HTTP-000001", result.bankReference)
    }

    @Test
    fun `history records the canonical states in order and skips the approval states`() {
        startWorker()
        val stub = newStub("happy-path-history")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        assertEquals(
            listOf(
                "VALIDATING",
                "VALIDATED",
                "FUNDS_RESERVED",
                "FX_QUOTE_VALIDATED",
                "SUBMITTING_TO_BANK",
                "SUBMITTED_TO_BANK",
                "AWAITING_BANK_CONFIRMATION",
                "COMPLETED",
            ),
            statesOf(stub),
        )
    }

    @Test
    fun `the completing run marks the ledger and notifies the customer, and unwinds nothing`() {
        startWorker()
        val stub = newStub("happy-path-side-effects")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        assertEquals(
            listOf(
                "validatePayout",
                "reserveFunds",
                "validateFxQuote",
                "submitToRail",
                "markPayout",
                "notify",
            ),
            activities.steps(),
        )
        assertTrue(activities.callsTo("releaseReservedFunds").isEmpty(), "nothing to release")
        assertTrue(activities.callsTo("reverseRailInstruction").isEmpty(), "nothing to reverse")
        assertTrue(activities.callsTo("pollBankStatus").isEmpty(), "the callback arrived, so no polling")
    }

    @Test
    fun `a closed payout cannot be restarted under the same workflow id`() {
        startWorker()
        val first = newStub("reuse-policy")
        start(first, payoutRequest(amountMinor = 25_000))
        awaitStatus(first, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        first.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        assertEquals(BusinessStatus.COMPLETED, resultOf(first).status)

        // PayoutController sets REJECT_DUPLICATE for exactly this case. The default,
        // AllowDuplicate, permits a second execution once the first has closed -- which for
        // a payout means paying twice. The policy is what makes the workflow id the
        // idempotency boundary, so it is pinned here rather than assumed.
        val duplicate = client.newWorkflowStub(
            PayoutWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("reuse-policy")
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE,
                )
                .build(),
        )
        assertFailsWith<WorkflowExecutionAlreadyStarted> {
            WorkflowClient.start(duplicate::processPayout, payoutRequest(amountMinor = 25_000))
        }
    }

    @Test
    fun `the query reports live workflow state, not a cached copy`() {
        startWorker()
        val stub = newStub("happy-path-query")
        start(stub, payoutRequest(amountMinor = 25_000))

        val inFlight = awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        assertEquals("po-000001", inFlight.payoutId)
        assertEquals(BusinessStatus.AWAITING_BANK_CONFIRMATION, inFlight.status)
        assertEquals("Waiting for bank payment status", inFlight.currentStep)

        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, stub.currentStatus().status)
    }
}
