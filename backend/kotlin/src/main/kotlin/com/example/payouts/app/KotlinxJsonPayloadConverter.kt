package com.example.payouts.app

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.PayloadConverter
import io.temporal.payload.context.SerializationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializerOrNull
import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.util.Optional

/**
 * kotlinx.serialization PayloadConverter.
 *
 * Declares the same encoding type as Jackson ("json/plain"), so registering it through
 * withPayloadConverterOverrides() replaces Jackson in the standard chain while keeping the
 * Null/ByteArray/Protobuf converters. Payloads stay readable in Temporal Web.
 *
 * Jackson is retained as an internal fallback for types kotlinx has no serializer for:
 * SDK-internal types, java.time, and the plain Strings that end up in failure details.
 */
class KotlinxJsonPayloadConverter(
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
    private val fallback: PayloadConverter = JacksonJsonPayloadConverter(),
) : PayloadConverter {

    companion object {
        // io.temporal.common.converter.EncodingKeys is package-private, so the wire values
        // are inlined here.
        const val METADATA_ENCODING_KEY = "encoding"
        const val JSON_PLAIN = "json/plain"
    }

    override fun getEncodingType(): String = JSON_PLAIN

    override fun toData(value: Any?): Optional<Payload> {
        if (value == null) return fallback.toData(value)
        // Only the runtime class is available here, so every top-level workflow and activity
        // parameter must be a concrete @Serializable data class.
        val serializer = serializerOrNull(value.javaClass) ?: return fallback.toData(value)
        return Optional.of(
            Payload.newBuilder()
                .putMetadata(METADATA_ENCODING_KEY, ByteString.copyFromUtf8(JSON_PLAIN))
                .setData(
                    ByteString.copyFrom(json.encodeToString(serializer, value), StandardCharsets.UTF_8),
                )
                .build(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> fromData(content: Payload, valueClass: Class<T>, valueType: Type): T {
        // The declared type is available here, so generics survive.
        val serializer = serializerOrNull(valueType)
            ?: return fallback.fromData(content, valueClass, valueType)
        return json.decodeFromString(serializer, content.data.toString(StandardCharsets.UTF_8)) as T
    }

    override fun withContext(context: SerializationContext): PayloadConverter = this
}
