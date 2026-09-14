package com.example.payouts.workflow

import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.domain.SettlementThresholds
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.payoutRequest
import io.temporal.api.enums.v1.EventType
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the bank confirms, decided by the amount.
 *
 * Below [SettlementThresholds.SYNC_BELOW_MINOR] the rail answers inside a single activity, so
 * the workflow never parks and never starts the bank deadline. At or above it the instruction
 * is confirmed out of band and the workflow waits durably on a signal, which the rest of this
 * suite covers. What is asserted here is mostly the absence of that wait.
 */
class PayoutWorkflowSyncSettlementTest : PayoutWorkflowTestBase() {

    private val lowValue = SettlementThresholds.SYNC_BELOW_MINOR - 1
    private val callbackValue = SettlementThresholds.SYNC_BELOW_MINOR

    @Test
    fun `a low-value payout settles inline without waiting for a callback`() {
        startWorker()
        val stub = newStub("sync-settle-completed")
        // No signal is ever sent, and none is needed.
        start(stub, payoutRequest(amountMinor = lowValue))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertEquals(FailureCategory.NONE, result.failureCategory)
        assertEquals(1, activities.callsTo("settleWithBank").size)

        val states = statesOf(stub)
        assertContains(states, "SETTLING_WITH_BANK")
        assertFalse(
            "AWAITING_BANK_CONFIRMATION" in states,
            "a low-value payout must not park on a callback: $states",
        )
        assertFalse(
            "POLLING_BANK_STATUS" in states,
            "and it must not fall through to polling either: $states",
        )
        assertTrue(activities.callsTo("pollBankStatus").isEmpty(), "nothing to poll for")
    }

    @Test
    fun `the inline settlement carries the idempotency key, like every other bank call`() {
        startWorker()
        val stub = newStub("sync-settle-idempotency")
        start(stub, payoutRequest(amountMinor = lowValue))
        resultOf(stub)

        assertEquals(
            "po-000001-rail-1",
            activities.callsTo("settleWithBank").single().idempotencyKey,
            "settling is a call about an instruction already submitted under that key",
        )
    }

    @Test
    fun `a refused inline settlement compensates and is flagged BANK_REJECTED`() {
        // The weighted roll is in the production activity; the fake always settles, so a
        // refusal is injected the way every other failure in this suite is.
        scenarios.put(
            "po-000001",
            ScenarioConfig(behavior = Behavior.REJECTED, step = "settleWithBank"),
        )
        startWorker()
        val stub = newStub("sync-settle-rejected")
        start(stub, payoutRequest(amountMinor = lowValue))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.FAILED, result.status)
        assertEquals(FailureCategory.BANK_REJECTED, result.failureCategory)
        assertContains(statesOf(stub), "COMPENSATED")
        // It reached the rail, so the instruction has to be reversed as well as the funds released.
        assertEquals(1, activities.callsTo("reverseRailInstruction").size)
        assertEquals(1, activities.callsTo("releaseReservedFunds").size)
    }

    @Test
    fun `a refusal is not retried, unlike a poll that reports pending`() {
        scenarios.put(
            "po-000001",
            ScenarioConfig(behavior = Behavior.REJECTED, step = "settleWithBank"),
        )
        startWorker()
        val stub = newStub("sync-settle-no-retry")
        start(stub, payoutRequest(amountMinor = lowValue))
        resultOf(stub)

        // BankRejected is on the stub's doNotRetry list, so a refusal is not retried.
        assertEquals(1, activities.callsTo("settleWithBank").size)
    }

    @Test
    fun `at the threshold exactly, the payout waits for the callback instead`() {
        startWorker()
        val stub = newStub("sync-settle-boundary")
        // settlesSynchronously is a strict `<`, so the threshold value itself is not low-value.
        start(stub, payoutRequest(amountMinor = callbackValue))

        val parked = awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        assertEquals("Waiting for bank payment status", parked.currentStep)
        assertTrue(activities.callsTo("settleWithBank").isEmpty(), "it is waiting, not settling")
    }

    @Test
    fun `the inline path starts no timer at all`() {
        startWorker()
        val stub = newStub("sync-settle-no-timer")
        start(stub, payoutRequest(amountMinor = lowValue))
        resultOf(stub)

        // The bank deadline is a workflow timer; a low-value payout starts no timers at all.
        val timers = client.fetchHistory("sync-settle-no-timer").history.eventsList
            .count { it.eventType == EventType.EVENT_TYPE_TIMER_STARTED }
        assertEquals(0, timers, "no approval wait and no bank wait means no timers")
    }
}
