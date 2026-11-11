package com.example.payouts.app

import io.temporal.client.WorkflowClient
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.workflowstreams.WorkflowStreamClient
import io.temporal.workflowstreams.WorkflowStreamClientOptions
import io.temporal.workflowstreams.WorkflowStreamItem
import io.temporal.workflowstreams.WorkflowStreamOptions
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Stream items are encoded per item by converters the stream library is handed explicitly: it
 * cannot see the worker's data converter from workflow code. Every publisher and subscriber
 * goes through here so all of them use [KotlinxJsonPayloadConverter], and items stay readable
 * JSON inside the stream's base64 envelope.
 */
object StreamCodec {
    private val converter = KotlinxJsonPayloadConverter()
    private val decoder = DefaultDataConverter(converter)

    /** 200ms: the batch interval the docs give for a live UI. The default is 2s. */
    private val BATCH_INTERVAL: Duration = Duration.ofMillis(200)

    fun workflowOptions(): WorkflowStreamOptions =
        WorkflowStreamOptions.newBuilder().setPayloadConverters(converter).build()

    fun clientOptions(): WorkflowStreamClientOptions =
        WorkflowStreamClientOptions.newBuilder()
            .setBatchInterval(BATCH_INTERVAL)
            .setPayloadConverters(converter)
            .build()

    /**
     * A subscriber's client. Each poll is an update, and its history event records the caller's
     * client identity, so [identity] names the subscriber in B's history. It defaults to
     * `pid@host`, which does not say which of the two subscribers sent the poll.
     */
    fun subscriber(client: WorkflowClient, workflowId: String, identity: String): WorkflowStreamClient =
        WorkflowStreamClient.newInstance(
            WorkflowClient.newInstance(client.workflowServiceStubs, client.options.toBuilder().setIdentity(identity).build()),
            workflowId,
            clientOptions(),
        )

    inline fun <reified T> decode(item: WorkflowStreamItem): T = decode(item, T::class.java)

    fun <T> decode(item: WorkflowStreamItem, type: Class<T>): T = decoder.fromPayload(item.payload, type, type)

    /** The item's JSON, undecoded. The SSE bridge forwards it as-is. */
    fun rawJson(item: WorkflowStreamItem): String = item.payload.data.toString(StandardCharsets.UTF_8)
}
