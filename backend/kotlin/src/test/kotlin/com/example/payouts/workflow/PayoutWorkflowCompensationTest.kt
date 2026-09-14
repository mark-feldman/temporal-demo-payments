package com.example.payouts.workflow

import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.workflow.ApprovalDecisionRequest
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.payoutRequest
import io.temporal.api.enums.v1.EventType
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Retries and the saga unwind.
 *
 * These tests do not poll a Query while a retry is in flight: time skipping is locked while a
 * Query is polled, and activity retry backoff is a server timer, so the workflow would not
 * progress. Signals are scheduled on the test server's clock with `registerDelayedCallback`
 * and the assertion blocks on the result.
 */
class PayoutWorkflowCompensationTest : PayoutWorkflowTestBase() {

    @Test
    fun `two transient rail failures retry and succeed on the third attempt`() {
        scenarios.put(
            "po-000001",
            ScenarioConfig(behavior = Behavior.FAIL_TRANSIENT, transientFailures = 2, step = "submitToRail"),
        )
        startWorker()
        val stub = newStub("compensation-transient")
        start(stub, payoutRequest(amountMinor = 25_000))

        // Well after the ~2s of retry backoff and well inside the 45s bank deadline.
        env.registerDelayedCallback(Duration.ofSeconds(30)) {
            runCatching { stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED)) }
        }
        val result = resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertEquals(3, result.railAttempts, "two failures then a success -- the third attempt is the one that lands")
        assertEquals(3, activities.callsTo("submitToRail").size)
    }

    @Test
    fun `the idempotency key is identical on every rail attempt`() {
        scenarios.put(
            "po-000001",
            ScenarioConfig(behavior = Behavior.FAIL_TRANSIENT, transientFailures = 2, step = "submitToRail"),
        )
        startWorker()
        val stub = newStub("compensation-idempotency")
        start(stub, payoutRequest(amountMinor = 25_000))
        env.registerDelayedCallback(Duration.ofSeconds(30)) {
            runCatching { stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED)) }
        }
        resultOf(stub)

        val submissions = activities.callsTo("submitToRail")
        assertEquals(3, submissions.size)
        assertEquals(listOf(1, 2, 3), submissions.map { it.attempt })
        // Retries reuse one key rather than minting a new one per attempt.
        assertEquals(
            setOf("po-000001-rail-1"),
            submissions.map { it.idempotencyKey }.toSet(),
            "every attempt must carry the same idempotency key",
        )
    }

    @Test
    fun `a non-retryable rail rejection stops at once and unwinds`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))
        startWorker()
        val stub = newStub("compensation-permanent")
        start(stub, payoutRequest(amountMinor = 25_000))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.FAILED, result.status)
        assertEquals(FailureCategory.RAIL_PERMANENT, result.failureCategory)
        assertEquals(
            1,
            activities.callsTo("submitToRail").size,
            "non-retryable means one attempt -- this is what distinguishes it from retry exhaustion",
        )
    }

    @Test
    fun `the unwind runs in reverse registration order and records the outcome`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))
        startWorker()
        val stub = newStub("compensation-order")
        start(stub, payoutRequest(amountMinor = 25_000))
        resultOf(stub)

        // Both compensations are registered before the activity they undo, so both exist even
        // though submitToRail never returned. Reverse order puts the bank first.
        assertEquals(
            listOf("reverseRailInstruction", "releaseReservedFunds", "markPayout", "notify"),
            activities.steps().drop(activities.steps().indexOf("submitToRail") + 1),
        )
        assertEquals("FAILED", activities.callsTo("markPayout").single().outcome)
        assertEquals("REV-HTTP-000001", stub.currentStatus().reversalReference)
        assertContains(statesOf(stub), "COMPENSATING")
        assertContains(statesOf(stub), "COMPENSATED")
    }

    @Test
    fun `a flaky ledger cannot stop the compensation from completing`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))
        // The release stub has maximumAttempts unset, so it is unlimited.
        activities.releaseFailures = 3
        startWorker()
        val stub = newStub("compensation-flaky-ledger")
        start(stub, payoutRequest(amountMinor = 25_000))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.FAILED, result.status)
        assertEquals(
            4,
            activities.callsTo("releaseReservedFunds").size,
            "three failures then the attempt that succeeds",
        )
        assertContains(statesOf(stub), "COMPENSATED")
    }

    @Test
    fun `the retry policies actually scheduled are the ones the demo claims`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))
        startWorker()
        val stub = newStub("compensation-retry-policy")
        start(stub, payoutRequest(amountMinor = 25_000))
        resultOf(stub)

        // Read out of history rather than off an options object: the stubs are private to the
        // workflow, and the scheduled policy is the one that ran.
        val scheduled = client.fetchHistory("compensation-retry-policy").history.eventsList
            .filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .associate { it.activityTaskScheduledEventAttributes.activityType.name to it.activityTaskScheduledEventAttributes }

        // 0 on the getter means unset, which is unlimited, not "zero attempts".
        listOf("ReleaseReservedFunds", "ReverseRailInstruction").forEach { activity ->
            val attributes = scheduled[activity] ?: fail("no $activity was scheduled: ${scheduled.keys}")
            assertEquals(
                0,
                attributes.retryPolicy.maximumAttempts,
                "$activity must be scheduled with maximumAttempts unset; setting it to 0 " +
                    "explicitly means 'use the default'",
            )
        }

        // The business activities are capped, at the randomised budget the scenario amounts in
        // app.js are checked against.
        val rail = scheduled["SubmitToRail"] ?: fail("no SubmitToRail was scheduled: ${scheduled.keys}")
        assertTrue(
            rail.retryPolicy.maximumAttempts in DEMO_MIN_ATTEMPTS..DEMO_MAX_ATTEMPTS,
            "the demo policy draws $DEMO_MIN_ATTEMPTS-$DEMO_MAX_ATTEMPTS attempts per " +
                "execution, but this run scheduled ${rail.retryPolicy.maximumAttempts}",
        )
    }

    @Test
    fun `a cancelled payout is marked CANCELLED in the ledger, not FAILED`() {
        startWorker()
        val stub = newStub("compensation-cancelled-outcome")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)
        stub.approve(ApprovalDecisionRequest(approved = false, approver = "dana"))
        val result = resultOf(stub)

        assertEquals(BusinessStatus.CANCELLED, result.status)
        assertEquals("CANCELLED", activities.callsTo("markPayout").single().outcome)
    }

    @Test
    fun `the customer is notified on the failure path as well as the happy one`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))
        startWorker()
        val stub = newStub("compensation-notify")
        start(stub, payoutRequest(amountMinor = 25_000))
        resultOf(stub)

        assertTrue(activities.callsTo("notify").isNotEmpty(), "a failed payout still tells the customer")
    }

    @Test
    fun `a transient failure on an earlier step retries there instead`() {
        // Failure injection is targeted by step, so the same behaviour can be aimed anywhere
        // in the chain -- here at the ledger reservation rather than the rail.
        scenarios.put(
            "po-000001",
            ScenarioConfig(behavior = Behavior.FAIL_TRANSIENT, transientFailures = 1, step = "reserveFunds"),
        )
        startWorker()
        val stub = newStub("compensation-early-retry")
        start(stub, payoutRequest(amountMinor = 25_000))
        env.registerDelayedCallback(Duration.ofSeconds(30)) {
            runCatching { stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED)) }
        }
        val result = resultOf(stub)

        assertEquals(BusinessStatus.COMPLETED, result.status)
        assertEquals(2, activities.callsTo("reserveFunds").size)
        assertEquals(1, activities.callsTo("submitToRail").size, "the rail itself was never flaky")
    }
}
