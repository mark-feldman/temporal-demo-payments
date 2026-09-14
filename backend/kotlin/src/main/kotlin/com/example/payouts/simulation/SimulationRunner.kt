package com.example.payouts.simulation

import com.example.payouts.api.BusinessMetrics
import com.example.payouts.api.PayoutController
import com.example.payouts.api.StartPayoutBody
import com.example.payouts.model.domain.ApprovalThresholds
import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.domain.SettlementThresholds
import com.example.payouts.model.workflow.ApprovalDecisionRequest
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.scenario.Behavior
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

data class SimulationConfig(
    val ratePerSecond: Int = 5,
    val durationSeconds: Int = 300,
    val maxInFlight: Int = 250,
    val successPct: Int = 35,
    val transientPct: Int = 25,
    val permanentPct: Int = 15,
    val approvalPct: Int = 15,
    val unknownPct: Int = 10,
    /** Of the payouts that need a human, the share that gets one. The rest are declined. */
    val approvalApprovedPct: Int = 80,
)

data class SimulationStatus(
    val state: String,
    val config: SimulationConfig,
    val started: Int,
    val inFlight: Int,
    val maxInFlight: Int,
    val completed: Int,
    val failed: Int,
    val compensated: Int,
    val awaitingApproval: Int,
    val unknown: Int,
    val retries: Int,
    val elapsedSeconds: Int,
)

/**
 * All randomisation lives here, on the backend side. Math.random() inside workflow code
 * would break replay; here it is simply a load generator.
 */
@Component
@ConditionalOnProperty(name = ["demo.role"], havingValue = "primary", matchIfMissing = true)
class SimulationRunner(
    private val payouts: PayoutController,
    private val metrics: BusinessMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var job: Job? = null
    private var config = SimulationConfig()
    private var startedAtMs = 0L

    private val started = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val completed = AtomicInteger()
    private val failed = AtomicInteger()
    private val compensated = AtomicInteger()
    private val awaitingApproval = AtomicInteger()
    private val unknown = AtomicInteger()
    private val retries = AtomicInteger()

    @Volatile private var state = "idle"

    fun start(cfg: SimulationConfig): SimulationStatus {
        if (state == "running") return status()
        config = cfg
        state = "running"
        startedAtMs = System.currentTimeMillis()
        listOf(started, inFlight, completed, failed, compensated, awaitingApproval, unknown, retries)
            .forEach { it.set(0) }

        job = scope.launch {
            val intervalMs = (1000.0 / cfg.ratePerSecond).toLong().coerceAtLeast(1)
            val deadline = System.currentTimeMillis() + cfg.durationSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                if (inFlight.get() < cfg.maxInFlight) {
                    launch { fireOne() }
                }
                delay(intervalMs.milliseconds)
            }
            state = "idle"
        }
        return status()
    }

    fun stop(): SimulationStatus {
        state = "stopping"
        job?.cancel()
        job = null
        state = "idle"
        return status()
    }

    fun status() = SimulationStatus(
        state = state,
        config = config,
        started = started.get(),
        inFlight = inFlight.get(),
        maxInFlight = config.maxInFlight,
        completed = completed.get(),
        failed = failed.get(),
        compensated = compensated.get(),
        awaitingApproval = awaitingApproval.get(),
        unknown = unknown.get(),
        retries = retries.get(),
        elapsedSeconds = if (startedAtMs == 0L) 0 else ((System.currentTimeMillis() - startedAtMs) / 1000).toInt(),
    )

    private suspend fun fireOne() = withContext(Dispatchers.IO) {
        inFlight.incrementAndGet()
        try {
            val body = randomBody()
            val result = payouts.start(body)
            started.incrementAndGet()

            // A bank callback is only sent to a payout that is actually waiting for one.
            // Below SettlementThresholds.SYNC_BELOW_MINOR the workflow settles inline
            // through the settleWithBank activity and never reaches the wait, so a callback
            // there is a signal nobody reads -- one wasted Signal, one wasted Workflow Task
            // and one more history event per payout. The condition derives from the same
            // threshold the workflow branches on rather than restating the number.
            //
            // Still sent for the larger amounts, the way a real rail would: asynchronously,
            // after a short delay. Without it those payouts sit out the 45s deadline and
            // resolve by polling, which takes ~67s (measured) and makes every scenario look
            // like the unknown-status one.
            val waitsForCallback = !SettlementThresholds.settlesSynchronously(body.amountMinor) &&
                body.behavior != Behavior.ACCEPTED_NO_CALLBACK &&
                body.behavior != Behavior.FAIL_PERMANENT
            if (waitsForCallback) {
                scope.launch {
                    delay(Random.nextLong(1_000, 8_000).milliseconds)
                    runCatching {
                        payouts.bankStatus(result.workflowId, BankStatusUpdateRequest(BankStatus.COMPLETED, ""))
                    }
                }
            }

            // Approvals, 80/20. Nothing used to send this signal at all, so every one of the
            // approval-scenario payouts sat until the 30s deadline fired and cancelled --
            // 4,577 of them on the last run, none approved. That is the same unfairness the
            // bank callback above exists to avoid, and it went unnoticed because a cancelled
            // payout is still a Completed workflow execution: processPayout catches and
            // returns rather than failing, so only businessStatus tells them apart.
            //
            // The roll is here, in the load generator, not in workflow code.
            if (ApprovalThresholds.tierFor(body.amountMinor) != ApprovalTier.NONE) {
                val approved = Random.nextInt(100) < config.approvalApprovedPct
                scope.launch {
                    delay(Random.nextLong(1_000, 12_000).milliseconds)
                    runCatching {
                        payouts.approve(
                            result.workflowId,
                            ApprovalDecisionRequest(
                                approved = approved,
                                approver = "sim-ops",
                                note = if (approved) "within limits" else "declined by policy",
                            ),
                        )
                    }
                }
            }
            when (body.behavior) {
                Behavior.PASS -> completed.incrementAndGet()
                Behavior.FAIL_TRANSIENT -> { retries.addAndGet(body.transientFailures); completed.incrementAndGet() }
                Behavior.FAIL_PERMANENT -> { failed.incrementAndGet(); compensated.incrementAndGet() }
                Behavior.ACCEPTED_NO_CALLBACK -> unknown.incrementAndGet()
                else -> Unit
            }
            if (body.amountMinor >= 50_000) awaitingApproval.incrementAndGet()
        } catch (e: Exception) {
            log.debug("simulation start failed: {}", e.message)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * Below the sync-settlement threshold: the bank answers inline, so these never wait on a
     * callback and never spend the 45s deadline. This is the bulk of the load.
     */
    private fun lowValue() = Random.nextLong(1_000, SettlementThresholds.SYNC_BELOW_MINOR)

    /**
     * Above the sync threshold but below the L1 approval threshold: confirmed out of band, so
     * it exercises the durable wait, without also pulling in a human. The unknown-status
     * scenario HAS to sit in this band -- a low-value one would settle inline and never reach
     * the polling path it exists to demonstrate.
     */
    private fun callbackValue() =
        Random.nextLong(SettlementThresholds.SYNC_BELOW_MINOR, ApprovalThresholds.L1_FROM_MINOR)

    private fun randomBody(): StartPayoutBody {
        val roll = Random.nextInt(100)
        val c = config
        return when {
            roll < c.successPct ->
                StartPayoutBody(scenario = "sim-success", amountMinor = lowValue())
            roll < c.successPct + c.transientPct ->
                StartPayoutBody(
                    scenario = "sim-retry", amountMinor = lowValue(),
                    behavior = Behavior.FAIL_TRANSIENT, transientFailures = Random.nextInt(1, 3),
                )
            roll < c.successPct + c.transientPct + c.permanentPct ->
                StartPayoutBody(
                    scenario = "sim-failed", amountMinor = lowValue(),
                    behavior = Behavior.FAIL_PERMANENT,
                )
            roll < c.successPct + c.transientPct + c.permanentPct + c.approvalPct ->
                StartPayoutBody(scenario = "sim-approval", amountMinor = Random.nextLong(60_000, 400_000))
            else ->
                StartPayoutBody(
                    scenario = "sim-unknown", amountMinor = callbackValue(),
                    behavior = Behavior.ACCEPTED_NO_CALLBACK,
                )
        }.copy(
            rail = Rail.entries.random(),
            region = Region.entries.random(),
            customerId = "cust-%04d".format(Random.nextInt(1, 500)),
        )
    }
}
