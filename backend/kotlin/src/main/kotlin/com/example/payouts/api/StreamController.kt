package com.example.payouts.api

import com.example.payouts.app.StreamCodec
import com.example.payouts.model.memo.*
import com.example.payouts.workflow.requestWorkflowIdFor
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionDescription
import io.temporal.client.WorkflowNotFoundException
import io.temporal.workflowstreams.SubscribeOptions
import io.temporal.workflowstreams.WorkflowStreamItem
import io.temporal.workflowstreams.WorkflowStreamListener
import jakarta.annotation.PreDestroy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors

/**
 * What the browser reads for scenario 6.
 *
 *  - `/streams/{id}/events` bridges a Workflow Stream to server-sent events. Each SSE `id` is the
 *    item's stream offset, so the browser's automatic reconnect sends it back as Last-Event-ID
 *    and the bridge resumes one past it. `?from=` does the same for a reconnect the page makes
 *    itself, since EventSource cannot set that header.
 *  - `/payouts/{id}/memo` is a workflow's memo, read with describe. It needs no worker, so it
 *    still answers while every worker is down.
 */
@RestController
@RequestMapping("/demo-api")
class StreamController(private val client: WorkflowClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Writes to the HTTP response block, so they run here rather than on the stream's poll executor. */
    private val writers = Executors.newCachedThreadPool { r -> Thread(r, "sse-writer").apply { isDaemon = true } }

    @PreDestroy
    fun stopWriters() {
        writers.shutdownNow()
    }

    @GetMapping("/streams/{workflowId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable workflowId: String,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
        @RequestParam("from", required = false) from: Long?,
    ): SseEmitter {
        val emitter = SseEmitter(0L) // no server-side timeout: the stream ends when the workflow does
        val start = lastEventId?.toLongOrNull()?.plus(1) ?: from ?: 0L

        val status = statusOf(workflowId)
        when {
            // A starts B a moment after the page subscribes. A normal close makes EventSource
            // reconnect, after the retry interval given here.
            status == null && statusOf(requestWorkflowIdFor(workflowId)) == RUNNING -> return emitter.endWith(
                SseEmitter.event().name("waiting").reconnectTime(500).data(notice(workflowId)),
            )
            // A closed without starting B, so B never will.
            status == null -> return emitter.endWith(closed(workflowId, NOT_STARTED))
            // The log lives in workflow memory and is gone once the workflow closes.
            status != RUNNING -> return emitter.endWith(closed(workflowId))
        }

        val stream = StreamCodec.subscriber(client, workflowId, "demo page · SSE bridge")
        val subscription = stream.subscribe(
            SubscribeOptions.newBuilder().setFromOffset(start).build(),
            object : WorkflowStreamListener {
                override fun onNext(item: WorkflowStreamItem): CompletionStage<Void> =
                    CompletableFuture.runAsync({ emitter.send(item.toSse()) }, writers)

                override fun onCompleted() {
                    writers.execute { runCatching { emitter.endWith(closed(workflowId)) } }
                }

                override fun onError(error: Throwable) {
                    log.warn("stream {} failed: {}", workflowId, error.message)
                    emitter.completeWithError(error)
                }
            },
        )
        log.info("SSE subscribed to {} from offset {}", workflowId, start)
        // The browser went away, or the stream ended: either way stop polling.
        val release = Runnable { subscription.close(); stream.close() }
        emitter.onCompletion(release)
        emitter.onError { release.run() }
        return emitter
    }

    /** 404 for a workflow that does not exist; any other failure is an error, not a 404. */
    @GetMapping("/payouts/{workflowId}/memo")
    fun memo(@PathVariable workflowId: String): ResponseEntity<StreamedPaymentView> = try {
        ResponseEntity.ok(client.newUntypedWorkflowStub(workflowId).describe().toView(workflowId))
    } catch (e: WorkflowNotFoundException) {
        ResponseEntity.notFound().build()
    }

    private fun WorkflowStreamItem.toSse() =
        SseEmitter.event().id(offset.toString()).name(topic).data(StreamCodec.rawJson(this), MediaType.APPLICATION_JSON)

    /** null when the workflow does not exist. */
    private fun statusOf(workflowId: String): WorkflowExecutionStatus? = try {
        client.newUntypedWorkflowStub(workflowId).describe().status
    } catch (e: WorkflowNotFoundException) {
        null
    }

    /** Tells the page the workflow has closed, with its status, so it stops reconnecting. */
    private fun closed(
        workflowId: String,
        status: String = runCatching { statusOf(workflowId)?.name }.getOrNull() ?: "UNKNOWN",
    ): SseEmitter.SseEventBuilder = SseEmitter.event().name("closed").data(notice(workflowId, status))

    /** The data of the `waiting` and `closed` events, encoded rather than interpolated: ids are user input. */
    private fun notice(workflowId: String, status: String? = null) = Json.encodeToString(StreamNotice(workflowId, status))

    private fun SseEmitter.endWith(event: SseEmitter.SseEventBuilder): SseEmitter = apply {
        send(event)
        complete()
    }

    private companion object {
        val RUNNING = WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING
        const val NOT_STARTED = "NOT_STARTED"
    }
}

@Serializable
private data class StreamNotice(val workflowId: String, val status: String? = null)

/** A workflow's memo, decoded. A has `relay`, B has `stream`; both have the rest. */
@Serializable
data class StreamedPaymentView(
    val workflowId: String,
    val executionStatus: String,
    val payment: PaymentMemo? = null,
    val progress: ProgressMemo? = null,
    val stages: StagesMemo? = null,
    val stream: StreamMemo? = null,
    val relay: RelayMemo? = null,
    val notification: NotificationMemo? = null,
    val outcome: OutcomeMemo? = null,
)

private fun WorkflowExecutionDescription.toView(workflowId: String) = StreamedPaymentView(
    workflowId = workflowId,
    executionStatus = status.name.removePrefix("WORKFLOW_EXECUTION_STATUS_"),
    payment = memo(MemoKeys.PAYMENT),
    progress = memo(MemoKeys.PROGRESS),
    stages = memo(MemoKeys.STAGES),
    stream = memo(MemoKeys.STREAM),
    relay = memo(MemoKeys.RELAY),
    notification = memo(MemoKeys.NOTIFICATION),
    outcome = memo(MemoKeys.OUTCOME),
)

private inline fun <reified T> WorkflowExecutionDescription.memo(key: String): T? =
    getMemo(key, T::class.java, T::class.java)
