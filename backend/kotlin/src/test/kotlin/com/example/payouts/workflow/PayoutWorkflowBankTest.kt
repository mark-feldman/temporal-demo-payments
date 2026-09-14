package com.example.payouts.workflow

import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.payoutRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What happens after the bank has the instruction.
 *
 * An absent callback is resolved by polling rather than by escalating. There is no sleep loop:
 * `pollBankStatus` throws a retryable failure while the bank reports "pending", so the retry
 * policy on the stub is the polling loop, and the backoff between polls is skipped by the test
 * server's clock.
 */
class PayoutWorkflowBankTest : PayoutWorkflowTestBase() {

    private fun stageNoCallback(
        payoutId: String = "po-000001",
        pollsBeforeResolution: Int = 3,
        resolvedStatus: String = "COMPLETED",
        pollingNeverResolves: Boolean = false,
    ) {
        scenarios.put(
            payoutId,
            ScenarioConfig(
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollsBeforeResolution = pollsBeforeResolution,
                resolvedStatus = resolvedStatus,
                pollingNeverResolves = pollingNeverResolves,
            ),
        )
    }

    @Test
    fun `a rejection on the callback fails the payout and unwinds it`() {
        startWorker()
        val stub = newStub("bank-rejected")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)

        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.REJECTED, "BANK-HTTP-000001"))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.FAILED, result.status)
        assertEquals(FailureCategory.BANK_REJECTED, result.failureCategory)
        assertTrue(activities.callsTo("pollBankStatus").isEmpty(), "a definite answer needs no polling")
        assertEquals(1, activities.callsTo("reverseRailInstruction").size)
        assertEquals(1, activities.callsTo("releaseReservedFunds").size)
    }

    @Test
    fun `an UNKNOWN callback is resolved by polling the bank, without escalating`() {
        stageNoCallback(pollsBeforeResolution = 2, resolvedStatus = "COMPLETED")
        startWorker()
        val stub = newStub("bank-poll-completed")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)

        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.UNKNOWN))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertEquals(FailureCategory.NONE, result.failureCategory)
        assertContains(statesOf(stub), "POLLING_BANK_STATUS")
        // Two "still pending" answers, then the real one on the third attempt.
        assertEquals(3, activities.callsTo("pollBankStatus").size)
        assertTrue(activities.callsTo("reverseRailInstruction").isEmpty(), "it settled; nothing to reverse")
    }

    @Test
    fun `polling that resolves to a rejection compensates`() {
        stageNoCallback(pollsBeforeResolution = 1, resolvedStatus = "REJECTED")
        startWorker()
        val stub = newStub("bank-poll-rejected")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)

        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.UNKNOWN))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.FAILED, result.status)
        assertEquals(FailureCategory.BANK_REJECTED, result.failureCategory)
        assertContains(statesOf(stub), "POLLING_BANK_STATUS")
        assertContains(statesOf(stub), "COMPENSATED")
        assertEquals(1, activities.callsTo("reverseRailInstruction").size)
    }

    @Test
    fun `polling that never resolves compensates and says so in failureCategory`() {
        stageNoCallback(pollingNeverResolves = true)
        startWorker()
        val stub = newStub("bank-poll-never")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)

        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.UNKNOWN))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.FAILED, result.status)
        // UNKNOWN_BANK_STATUS records that the instruction may already have settled.
        assertEquals(FailureCategory.UNKNOWN_BANK_STATUS, result.failureCategory)
        assertTrue(
            activities.callsTo("pollBankStatus").size >= 7,
            "the stub allows 7-10 attempts; polling must exhaust them before giving up",
        )
        assertContains(statesOf(stub), "COMPENSATED")
    }

    @Test
    fun `the unwind reverses at the bank before releasing our own reservation`() {
        stageNoCallback(pollingNeverResolves = true)
        startWorker()
        val stub = newStub("bank-reversal-order")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.UNKNOWN))
        resultOf(stub)

        assertEquals("REV-HTTP-000001", stub.currentStatus().reversalReference)

        val unwind = activities.steps().filter {
            it == "reverseRailInstruction" || it == "releaseReservedFunds"
        }
        assertEquals(
            listOf("reverseRailInstruction", "releaseReservedFunds"),
            unwind,
            "the saga unwinds in reverse registration order: the bank first, then our ledger",
        )
    }

    @Test
    fun `no callback at all falls through the deadline into polling`() {
        stageNoCallback(pollsBeforeResolution = 1, resolvedStatus = "COMPLETED")
        startWorker()
        val stub = newStub("bank-no-callback")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)

        // No signal is sent at all. Blocking on the result unlocks time skipping, the 45s
        // bank deadline fires, awaitBankStatus reports UNKNOWN and polling takes over.
        val result = resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertContains(statesOf(stub), "POLLING_BANK_STATUS")
        assertEquals(2, activities.callsTo("pollBankStatus").size)
    }
}
