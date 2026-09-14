package com.example.payouts.support

import com.example.payouts.model.domain.BusinessStatus
import io.temporal.common.SearchAttributeUpdate
import io.temporal.common.interceptors.WorkerInterceptorBase
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Turns the workflow's own state machine into an event stream, so tests can *wait for* a state
 * rather than repeatedly asking whether it has arrived yet.
 *
 * `PayoutWorkflowImpl.advance()` calls `Workflow.upsertTypedSearchAttributes` on every single
 * transition -- that is how the `businessStatus` attribute stays current for the visibility
 * query the dashboards use. `WorkflowOutboundCallsInterceptor` sees each of those calls as it
 * happens, which is what makes the transition observable at the instant it occurs.
 *
 * ## Why not Awaitility
 *
 * Awaitility is on the classpath already (via `spring-boot-starter-test`) and is the right
 * tool when the only thing available is a condition you have to re-evaluate -- it beats a
 * hand-rolled loop on failure messages, timeout handling and readability. But its model is
 * polling: `pollDelay`, `pollInterval`, `pollThread`. It makes polling tidy; it does not make
 * it unnecessary.
 *
 * Here there is a real event, so the wait can be push-based instead: a latency floor of zero,
 * no repeated query tasks dispatched at the worker under test, and a timeout that is a
 * backstop for a stuck workflow rather than a tuning parameter. The waiting itself is plain
 * `java.util.concurrent` -- `CompletableFuture.anyOf` is precisely the standard combinator for
 * "whichever of these arrives first", and `computeIfAbsent` removes any need to lock around
 * "was it already reached". Nothing here is a bespoke synchronisation mechanism; the only
 * custom part is the interceptor, because no library can know that this workflow signals a
 * business transition by upserting that particular search attribute.
 *
 * ## Threading
 *
 * [record] runs on a workflow thread, so completion goes through `completeAsync`:
 * `CompletableFuture.complete` runs dependent stages on the completing thread, and a caller
 * who chained anything blocking onto one of these futures would otherwise run it inside the
 * workflow.
 *
 * ## Replay
 *
 * The interceptor fires again for transitions being replayed. Completing an already-completed
 * future is a no-op, so replay is harmless.
 */
class BusinessStatusListener : WorkerInterceptorBase() {

    private data class Transition(val workflowId: String, val status: BusinessStatus)

    private val arrivals = ConcurrentHashMap<Transition, CompletableFuture<BusinessStatus>>()
    private val history = ConcurrentHashMap<String, CopyOnWriteArrayList<BusinessStatus>>()

    /**
     * A future for one transition, created on first interest from either side. Whether the
     * waiter or the workflow gets here first does not matter: if the transition already
     * happened the future is already complete and [await] returns immediately.
     */
    private fun arrivalOf(workflowId: String, status: BusinessStatus): CompletableFuture<BusinessStatus> =
        arrivals.computeIfAbsent(Transition(workflowId, status)) { CompletableFuture() }

    /** Blocks until [workflowId] enters one of [wanted], and returns whichever it entered. */
    fun await(
        workflowId: String,
        vararg wanted: BusinessStatus,
        timeout: Duration = Duration.ofSeconds(30),
    ): BusinessStatus {
        require(wanted.isNotEmpty()) { "await needs at least one status to wait for" }
        val arrivals = wanted.map { arrivalOf(workflowId, it) }.toTypedArray()
        return try {
            CompletableFuture.anyOf(*arrivals)
                .get(timeout.toMillis(), TimeUnit.MILLISECONDS) as BusinessStatus
        } catch (e: TimeoutException) {
            throw AssertionError(
                "workflow '$workflowId' did not reach any of ${wanted.toList()} within " +
                    "$timeout. States seen: ${seen(workflowId)}",
                e,
            )
        }
    }

    /** Every state this workflow has passed through, in order. Used in failure messages. */
    fun seen(workflowId: String): List<BusinessStatus> = history[workflowId]?.toList() ?: emptyList()

    private fun record(workflowId: String, status: BusinessStatus) {
        history.computeIfAbsent(workflowId) { CopyOnWriteArrayList() }.addIfAbsent(status)
        // Off the workflow thread -- see the threading note above.
        arrivalOf(workflowId, status).completeAsync { status }
    }

    override fun interceptWorkflow(next: WorkflowInboundCallsInterceptor): WorkflowInboundCallsInterceptor =
        object : WorkflowInboundCallsInterceptorBase(next) {
            override fun init(outboundCalls: WorkflowOutboundCallsInterceptor) {
                next.init(
                    object : WorkflowOutboundCallsInterceptorBase(outboundCalls) {
                        override fun upsertTypedSearchAttributes(vararg updates: SearchAttributeUpdate<*>) {
                            super.upsertTypedSearchAttributes(*updates)
                            statusIn(updates)?.let { record(Workflow.getInfo().workflowId, it) }
                        }
                    },
                )
            }
        }

    private fun statusIn(updates: Array<out SearchAttributeUpdate<*>>): BusinessStatus? = updates
        .firstOrNull { it.key.name == BUSINESS_STATUS }
        ?.value?.orElse(null)
        ?.let { value -> runCatching { BusinessStatus.valueOf(value as String) }.getOrNull() }

    private companion object {
        const val BUSINESS_STATUS = "businessStatus"
    }
}
