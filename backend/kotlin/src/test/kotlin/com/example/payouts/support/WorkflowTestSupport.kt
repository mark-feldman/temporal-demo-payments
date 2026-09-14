package com.example.payouts.support

import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.workflow.PayoutStatusResponse
import com.example.payouts.model.workflow.ProcessPayoutRequest
import com.example.payouts.model.workflow.ProcessPayoutResponse
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.workflow.PayoutWorkflow
import com.example.payouts.workflow.TASK_QUEUE
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * Time skipping is LOCKED unless a client stub is blocked inside getResult -- verified in
 * TimeLockingInterceptor/IdempotentTimeLocker. Two consequences shape every test here:
 *
 *  1. Waiting for an intermediate state between `start` and `getResult` is race-free.
 *     Virtual time cannot run ahead, so the 30s approval and 45s bank deadlines cannot fire
 *     underneath the assertion.
 *  2. For the same reason, activity retry BACKOFF is also frozen during that window. A test
 *     that injects a transient failure must not wait for an intermediate state -- it would
 *     wait forever, because the workflow cannot progress until time moves. Those tests send
 *     their signal up front, or schedule it with `env.registerDelayedCallback`, and block on
 *     the result instead.
 *
 * Waiting itself is event-driven rather than polled: see [awaitStatus] and
 * [BusinessStatusListener].
 */
abstract class PayoutWorkflowTestBase {

    @TempDir
    lateinit var tmp: Path

    protected lateinit var env: TestWorkflowEnvironment
    protected lateinit var scenarios: ScenarioStore
    protected lateinit var activities: RecordingActivities

    /** Business-state transitions, pushed by a worker interceptor. See [awaitStatus]. */
    protected lateinit var statusEvents: BusinessStatusListener

    protected val client: WorkflowClient get() = env.workflowClient

    @BeforeEach
    fun setUpEnvironment() {
        scenarios = scenarioStoreIn(tmp)
        activities = RecordingActivities(scenarios)
        statusEvents = BusinessStatusListener()
        env = newPayoutTestEnvironment(statusEvents)
    }

    /** Starts the worker with the recording stand-in. Call after staging any ScenarioConfig. */
    protected fun startWorker() {
        env.startPayoutWorker(activities)
    }

    /** Starts the worker with something else -- used by the Mockito-style test. */
    protected fun startWorkerWith(vararg others: Any) {
        env.startPayoutWorker(*others)
    }

    @AfterEach
    fun tearDownEnvironment() {
        if (::env.isInitialized) env.close()
    }

    protected fun newStub(workflowId: String): PayoutWorkflow =
        client.newWorkflowStub(
            PayoutWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowId)
                .build(),
        )

    protected fun start(stub: PayoutWorkflow, request: ProcessPayoutRequest) {
        WorkflowClient.start(stub::processPayout, request)
    }

    protected fun resultOf(stub: PayoutWorkflow): ProcessPayoutResponse =
        WorkflowStub.fromTyped(stub).getResult(ProcessPayoutResponse::class.java)

    /**
     * Blocks until the workflow transitions into one of [wanted], then returns its full state.
     *
     * The wait is event-driven: [BusinessStatusListener] is notified by the workflow's own
     * `upsertTypedSearchAttributes` call the instant the transition happens. No sleeping, no
     * repeated Queries.
     *
     * Exactly ONE Query follows, to fetch the fields the transition event does not carry.
     * That read is consistent because every state this is used to wait for -- the two
     * AWAITING_* states and the terminal ones -- is immediately followed by a durable wait,
     * and virtual time is locked while the test holds no `getResult` call open, so nothing
     * can move the workflow on in between. If that assumption is ever broken the check below
     * fails loudly rather than quietly asserting against the wrong state.
     */
    protected fun awaitStatus(
        stub: PayoutWorkflow,
        vararg wanted: BusinessStatus,
        timeout: Duration = Duration.ofSeconds(30),
    ): PayoutStatusResponse {
        val execution = requireNotNull(WorkflowStub.fromTyped(stub).execution) {
            "the stub has no execution yet -- call start() before awaiting a status"
        }
        val workflowId = execution.workflowId
        val reached = statusEvents.await(workflowId, *wanted, timeout = timeout)

        val snapshot = stub.currentStatus()
        check(snapshot.status in wanted) {
            "workflow '$workflowId' transitioned into $reached but the Query already reports " +
                "${snapshot.status}. awaitStatus is only safe for states the workflow parks " +
                "on. States seen: ${statusEvents.seen(workflowId)}"
        }
        return snapshot
    }

    /** The state names from the Query's history list, without their detail text. */
    protected fun statesOf(stub: PayoutWorkflow): List<String> =
        stub.currentStatus().history.map { it.substringBefore(":") }
}
