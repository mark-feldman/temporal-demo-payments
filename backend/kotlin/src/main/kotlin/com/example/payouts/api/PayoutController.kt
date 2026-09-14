package com.example.payouts.api

import com.example.payouts.model.domain.*
import com.example.payouts.model.workflow.*
import com.example.payouts.scenario.Behavior
import com.example.payouts.scenario.ScenarioConfig
import com.example.payouts.scenario.ScenarioStore
import com.example.payouts.workflow.PayoutWorkflow
import com.example.payouts.workflow.TASK_QUEUE
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.newWorkflowStub
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.atomic.AtomicLong

data class StartPayoutBody(
    val scenario: String = "successful",
    val amountMinor: Long = 25_000,
    val currency: String = "USD",
    val rail: Rail = Rail.HTTP,
    val region: Region = Region.SG,
    val customerId: String = "cust-0001",
    val behavior: Behavior = Behavior.PASS,
    val transientFailures: Int = 2,
    val step: String = "submitToRail",
    /** How many polls report "still pending" before the bank gives a real answer. */
    val pollsBeforeResolution: Int = 3,
    /** What the bank eventually reports once polling resolves: COMPLETED or REJECTED. */
    val resolvedStatus: String = "COMPLETED",
    /** When true the bank never answers and polling exhausts its retries. */
    val pollingNeverResolves: Boolean = false,
)

data class StartPayoutResult(
    val payoutId: String,
    val workflowId: String,
    val runId: String,
    val temporalUrl: String,
)

@RestController
@RequestMapping("/demo-api")
class PayoutController(
    private val client: WorkflowClient,
    private val scenarios: ScenarioStore,
    private val metrics: BusinessMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seq = AtomicLong(System.currentTimeMillis() % 100_000)

    @PostMapping("/payouts")
    fun start(@RequestBody body: StartPayoutBody): StartPayoutResult {
        val payoutId = "po-%06d".format(seq.incrementAndGet())
        val workflowId = "payout-${body.scenario}-$payoutId"

        scenarios.put(
            payoutId,
            ScenarioConfig(
                behavior = body.behavior,
                transientFailures = body.transientFailures,
                step = body.step,
                pollsBeforeResolution = body.pollsBeforeResolution,
                resolvedStatus = body.resolvedStatus,
                pollingNeverResolves = body.pollingNeverResolves,
            ),
        )

        val stub = client.newWorkflowStub<PayoutWorkflow> {
            setWorkflowId(workflowId)
            setTaskQueue(TASK_QUEUE)
            // REJECT_DUPLICATE, not the default AllowDuplicate, which permits a second payout
            // once the first has closed.
            setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            setStaticSummary("Payout $payoutId - ${body.scenario}")
            setStaticDetails(
                "**${body.amountMinor / 100.0} ${body.currency}** over the ${body.rail} rail " +
                    "in ${body.region}.\n\nScenario: `${body.scenario}` / behaviour `${body.behavior}`.",
            )
        }

        val request = ProcessPayoutRequest(
            payoutId = payoutId,
            customerId = body.customerId,
            amount = Money(body.amountMinor, body.currency),
            rail = body.rail,
            region = body.region,
            scenarioName = body.scenario,
            idempotencyKey = "$payoutId-rail-1",
        )
        val execution = WorkflowClient.start(stub::processPayout, request)
        metrics.started.increment()
        log.info("started {} ({})", workflowId, body.scenario)

        return StartPayoutResult(
            payoutId = payoutId,
            workflowId = workflowId,
            runId = execution.runId,
            temporalUrl = "/namespaces/default/workflows/$workflowId/${execution.runId}/timeline",
        )
    }

    /** Backed by the workflow Query rather than a cache: Temporal holds the state. */
    @GetMapping("/payouts/{workflowId}/status")
    fun status(@PathVariable workflowId: String): ResponseEntity<PayoutStatusResponse> = runCatching {
        ResponseEntity.ok(client.newWorkflowStub(PayoutWorkflow::class.java, workflowId).currentStatus())
    }.getOrElse { ResponseEntity.notFound().build() }

    @PostMapping("/payouts/{workflowId}/approval")
    fun approve(@PathVariable workflowId: String, @RequestBody body: ApprovalDecisionRequest): Map<String, Any> {
        client.newWorkflowStub(PayoutWorkflow::class.java, workflowId).approve(body)
        return mapOf("sent" to true, "note" to "Signals are fire-and-forget - poll status to see the outcome")
    }

    @PostMapping("/payouts/{workflowId}/bank-status")
    fun bankStatus(@PathVariable workflowId: String, @RequestBody body: BankStatusUpdateRequest): Map<String, Any> {
        client.newWorkflowStub(PayoutWorkflow::class.java, workflowId).bankStatusUpdate(body)
        return mapOf("sent" to true)
    }
}
