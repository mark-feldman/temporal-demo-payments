package com.example.payouts.workflow

import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.workflow.ApprovalDecisionRequest
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.newPayoutTestEnvironment
import com.example.payouts.support.payoutRequest
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.WorkflowReplayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replay is the check that matters when workflow code changes: Temporal re-executes the
 * workflow function against a recorded history and compares the commands it produces against
 * the events already there. A mismatch is a non-determinism error, and in production it
 * blocks the workflow rather than failing it loudly.
 *
 * Histories are generated in-process rather than checked in, so the fixtures cannot go stale
 * against the current code. For the stronger guard -- replaying histories recorded by an
 * EARLIER version of the workflow -- drop `temporal workflow show --output json` exports into
 * `src/test/resources/histories/` and the last test here picks them up automatically.
 */
class PayoutWorkflowReplayTest : PayoutWorkflowTestBase() {

    /** Replays against a fresh environment carrying the production DataConverter. */
    private fun replay(history: WorkflowExecutionHistory) {
        // The single-argument overload spins up a default TestWorkflowEnvironment, which
        // means the default Jackson converter rather than this app's kotlinx one. Supplying
        // the environment keeps the replay on the same converter that wrote the payloads.
        val replayEnv = newPayoutTestEnvironment()
        try {
            WorkflowReplayer.replayWorkflowExecution(history, replayEnv, PayoutWorkflowImpl::class.java)
        } finally {
            replayEnv.close()
        }
    }

    @Test
    fun `a completed run replays without a non-determinism error`() {
        startWorker()
        val stub = newStub("replay-completed")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        assertEquals(BusinessStatus.COMPLETED, resultOf(stub).status)

        replay(client.fetchHistory("replay-completed"))
    }

    @Test
    fun `a compensated run replays without a non-determinism error`() {
        scenarios.put("po-000001", ScenarioConfig(behavior = Behavior.FAIL_PERMANENT, step = "submitToRail"))
        startWorker()
        val stub = newStub("replay-compensated")
        start(stub, payoutRequest(amountMinor = 25_000))
        assertEquals(BusinessStatus.FAILED, resultOf(stub).status)

        // Exercises the detached cancellation scope and the saga unwind on replay, which is
        // where a compensation registered in the wrong order would show up.
        replay(client.fetchHistory("replay-compensated"))
    }

    @Test
    fun `an approval run with timers and signals replays without a non-determinism error`() {
        startWorker()
        val stub = newStub("replay-approval")
        start(stub, payoutRequest(amountMinor = 250_000))
        awaitStatus(stub, BusinessStatus.AWAITING_APPROVAL)
        stub.approve(ApprovalDecisionRequest(approved = true, approver = "dana"))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        // The workflow randomises its retry cap with Workflow.newRandom
        // and orders two deadlines through timer callbacks. Both have to reproduce exactly.
        replay(client.fetchHistory("replay-approval"))
    }

    @Test
    fun `a polling run replays without a non-determinism error`() {
        scenarios.put(
            "po-000001",
            ScenarioConfig(
                behavior = Behavior.ACCEPTED_NO_CALLBACK,
                pollsBeforeResolution = 2,
                resolvedStatus = "COMPLETED",
            ),
        )
        startWorker()
        val stub = newStub("replay-polling")
        start(stub, payoutRequest(amountMinor = 25_000))
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        env.registerDelayedCallback(Duration.ofSeconds(5)) {
            runCatching { stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.UNKNOWN)) }
        }
        resultOf(stub)

        replay(client.fetchHistory("replay-polling"))
    }

    @Test
    fun `any histories recorded from a real run also replay`() {
        val dir = Path.of("src/test/resources/histories")
        val exports = if (dir.isDirectory()) {
            Files.list(dir).use { stream -> stream.filter { it.extension == "json" }.toList() }
        } else {
            emptyList()
        }
        assumeTrue(
            exports.isNotEmpty(),
            "no recorded histories in src/test/resources/histories -- drop " +
                "`temporal workflow show --output json` exports there to guard against " +
                "non-deterministic changes to the workflow",
        )

        val histories = exports.map { WorkflowExecutionHistory.fromJson(it.readText()) }
        val replayEnv = newPayoutTestEnvironment()
        try {
            val worker = replayEnv.newWorker(TASK_QUEUE)
            worker.registerWorkflowImplementationTypes(PayoutWorkflowImpl::class.java)
            val results = WorkflowReplayer.replayWorkflowExecutions(histories, false, worker)
            assertTrue(
                !results.hadAnyError(),
                "recorded histories failed to replay against the current workflow code: " +
                    results.allErrors().joinToString { "${it.workflowId}: ${it.exception.message}" },
            )
        } finally {
            replayEnv.close()
        }
    }
}
