package com.example.payouts.support

import com.example.payouts.activities.StageRunner
import com.example.payouts.activities.StreamRelayActivities
import com.example.payouts.activities.StreamRelayActivitiesImpl
import com.example.payouts.app.StreamCodec
import com.example.payouts.model.activity.RelayRequest
import com.example.payouts.model.activity.RelayResponse
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.stream.StageEvent
import com.example.payouts.model.stream.Topics
import com.example.payouts.model.workflow.StageUpdateSignal
import com.example.payouts.model.workflow.StartStreamedPaymentRequest
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.workflow.PaymentProcessingWorkflowImpl
import com.example.payouts.workflow.PaymentRequestWorkflow
import com.example.payouts.workflow.PaymentRequestWorkflowImpl
import com.example.payouts.workflow.TASK_QUEUE
import io.temporal.activity.Activity
import io.temporal.client.WorkflowClientOptions
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.common.interceptors.WorkerInterceptor
import io.temporal.common.interceptors.WorkerInterceptorBase
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase
import io.temporal.common.interceptors.WorkflowClientInterceptor
import io.temporal.common.interceptors.WorkflowClientInterceptorBase
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.WorkerFactoryOptions
import io.temporal.workflow.Workflow
import io.temporal.workflowstreams.SubscribeOptions
import io.temporal.workflowstreams.WorkflowStreamClient
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The streamed-payment tests run without time skipping. The relay heartbeats and the stage
 * activities retry in real time, and with time skipping on, virtual time is locked whenever the
 * test thread is not inside getResult (see PayoutWorkflowTestBase), which freezes retry backoff.
 *
 * [timeSkipping] is for a test with no retries that has to get past B's 15-minute wait for
 * the acknowledgement.
 */
fun newStreamedPaymentTestEnvironment(
    vararg interceptors: WorkerInterceptor,
    timeSkipping: Boolean = false,
    clientInterceptors: List<WorkflowClientInterceptor> = emptyList(),
): TestWorkflowEnvironment =
    TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setUseTimeskipping(timeSkipping)
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setNamespace("default")
                    .setDataConverter(productionDataConverter())
                    .setInterceptors(*clientInterceptors.toTypedArray())
                    .build(),
            )
            .apply { DEMO_SEARCH_ATTRIBUTES.forEach { (name, type) -> registerSearchAttribute(name, type) } }
            .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder().setWorkerInterceptors(*interceptors).build())
            .build(),
    )

/** Both workflows, the real stage activities with [stepDelay] before each step, and [relay] on the demo queue. */
fun TestWorkflowEnvironment.startStreamedPaymentWorker(
    scenarios: ScenarioStore,
    notifier: RecordingActivities,
    relay: StreamRelayActivities = StreamRelayActivitiesImpl(),
    stepDelay: kotlin.time.Duration = kotlin.time.Duration.ZERO,
) {
    val worker = newWorker(TASK_QUEUE)
    worker.registerWorkflowImplementationTypes(
        PaymentRequestWorkflowImpl::class.java,
        PaymentProcessingWorkflowImpl::class.java,
    )
    val runner = StageRunner(scenarios).apply { this.stepDelay = { stepDelay } }
    worker.registerActivitiesImplementations(
        com.example.payouts.activities.PaymentStageActivitiesImpl(runner),
        relay,
        notifier,
    )
    start()
}

fun streamedPaymentRequest(payoutId: String = "po-stream01") = StartStreamedPaymentRequest(
    payoutId = payoutId,
    customerId = "cust-0001",
    amount = Money(7_500, "USD"),
    rail = Rail.HTTP,
    region = Region.SG,
    scenarioName = "streamed",
)

/**
 * Completes when a workflow's method starts executing, which for the processing workflow means
 * its stream exists: the stream is created in the @WorkflowInit constructor, before `execute`.
 * Event-driven for the same reasons as [BusinessStatusListener].
 */
class WorkflowStartListener : WorkerInterceptorBase() {
    private val started = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    private fun startOf(workflowId: String) = started.computeIfAbsent(workflowId) { CompletableFuture() }

    fun await(workflowId: String, timeout: Duration = Duration.ofSeconds(30)) {
        startOf(workflowId).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun interceptWorkflow(next: WorkflowInboundCallsInterceptor): WorkflowInboundCallsInterceptor =
        object : WorkflowInboundCallsInterceptorBase(next) {
            override fun execute(input: WorkflowInboundCallsInterceptor.WorkflowInput): WorkflowInboundCallsInterceptor.WorkflowOutput {
                // Off the workflow thread, as in BusinessStatusListener.
                val id = Workflow.getInfo().workflowId
                startOf(id).completeAsync { }
                return super.execute(input)
            }
        }
}

/**
 * Fails the first `onStageUpdate` signal for [seq], before it is sent, as an unavailable
 * server would. The relay signals A through the worker's client, which is the environment's,
 * so the real relay's call fails.
 */
class FailingStageUpdateSignal(private val seq: Int) : WorkflowClientInterceptorBase() {
    private val failed = AtomicBoolean()

    override fun workflowClientCallsInterceptor(next: WorkflowClientCallsInterceptor): WorkflowClientCallsInterceptor =
        object : WorkflowClientCallsInterceptorBase(next) {
            override fun signal(input: WorkflowClientCallsInterceptor.WorkflowSignalInput): WorkflowClientCallsInterceptor.WorkflowSignalOutput {
                val update = input.arguments.singleOrNull() as? StageUpdateSignal
                if (input.signalName == "onStageUpdate" && update?.event?.seq == seq && failed.compareAndSet(false, true)) {
                    throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("signal to A failed (simulated)"))
                }
                return super.signal(input)
            }
        }
}

/** Fails at once and for good, as a relay that has used up its schedule-to-close would. */
class DeadRelay : StreamRelayActivities {
    override fun relayStageUpdates(request: RelayRequest): RelayResponse =
        throw ApplicationFailure.newNonRetryableFailure("relay gave up (simulated)", "RelayLost")
}

/**
 * The first attempt relays the first [relayFirst] status items to A, heartbeats only the first
 * one's offset -- as if the throttled heartbeat lagged -- and fails. Later attempts are the real
 * relay, which resumes from that heartbeat and so re-delivers the rest.
 */
class FailingFirstAttemptRelay(private val relayFirst: Int) : StreamRelayActivities {
    private val real = StreamRelayActivitiesImpl()

    override fun relayStageUpdates(request: RelayRequest): RelayResponse {
        val context = Activity.getExecutionContext()
        if (context.info.attempt > 1) return real.relayStageUpdates(request)

        val initiator = context.workflowClient
            .newWorkflowStub(PaymentRequestWorkflow::class.java, context.info.workflowId)
        WorkflowStreamClient.newInstance(context.workflowClient, request.processingWorkflowId, StreamCodec.clientOptions())
            .use { stream ->
                stream.subscribe(SubscribeOptions.newBuilder().setTopics(Topics.STATUS).build()).use { subscription ->
                    subscription.take(relayFirst).forEachIndexed { i, item ->
                        initiator.onStageUpdate(StageUpdateSignal(item.offset, StreamCodec.decode<StageEvent>(item)))
                        if (i == 0) context.heartbeat(item.offset)
                    }
                }
            }
        throw ApplicationFailure.newFailure("relay worker lost (simulated)", "RelayLost")
    }
}
