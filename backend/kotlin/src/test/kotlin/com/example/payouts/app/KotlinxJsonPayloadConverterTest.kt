package com.example.payouts.app

import com.example.payouts.model.activity.*
import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.workflow.*
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The converter is the reason the single-concrete-@Serializable-parameter rule exists, so the
 * rule is asserted here rather than only written down.
 *
 * `toData` receives the runtime object and nothing else -- no declared type, no generic
 * signature. That is why a sealed type, a bare generic or a top-level null loses its type
 * information at the boundary, and why every workflow method, activity method, signal and
 * query in this codebase takes exactly one flat data class.
 */
class KotlinxJsonPayloadConverterTest {

    private val converter = KotlinxJsonPayloadConverter()

    private fun json(value: Any?): String =
        converter.toData(value).orElseThrow().data.toString(StandardCharsets.UTF_8)

    private inline fun <reified T : Any> roundTrip(value: T): T {
        val payload = converter.toData(value).orElseThrow()
        return converter.fromData(payload, T::class.java, T::class.java)
    }

    @Test
    fun `declares the same encoding as Jackson, which is what makes it a replacement`() {
        // Registering an override keyed on "json/plain" displaces Jackson from the standard
        // chain while leaving the Null/ByteArray/Protobuf converters in place -- and keeps
        // payloads readable in Temporal Web.
        assertEquals("json/plain", converter.encodingType)
        assertEquals("json/plain", KotlinxJsonPayloadConverter.JSON_PLAIN)
        assertEquals("encoding", KotlinxJsonPayloadConverter.METADATA_ENCODING_KEY)

        val payload = converter.toData(Money(1, "USD")).orElseThrow()
        assertEquals(
            "json/plain",
            payload.getMetadataOrThrow("encoding").toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `workflow request and response survive a round trip intact`() {
        val request = ProcessPayoutRequest(
            payoutId = "po-000042",
            customerId = "cust-0007",
            amount = Money(250_000, "SGD"),
            rail = Rail.SFTP,
            region = Region.AU,
            scenarioName = "converter-test",
            idempotencyKey = "po-000042-rail-1",
        )
        assertEquals(request, roundTrip(request))

        val response = ProcessPayoutResponse(
            payoutId = "po-000042",
            status = BusinessStatus.FAILED,
            failureCategory = FailureCategory.UNKNOWN_BANK_STATUS,
            bankReference = "BANK-SFTP-000042",
            railAttempts = 3,
            message = "bank never confirmed",
        )
        assertEquals(response, roundTrip(response))
    }

    @Test
    fun `signal and query payloads survive a round trip intact`() {
        val approval = ApprovalDecisionRequest(approved = true, approver = "ops", note = "cleared")
        assertEquals(approval, roundTrip(approval))

        val callback = BankStatusUpdateRequest(BankStatus.REJECTED, "BANK-HTTP-000042")
        assertEquals(callback, roundTrip(callback))

        val status = PayoutStatusResponse(
            payoutId = "po-000042",
            status = BusinessStatus.COMPENSATED,
            currentStep = "Unwinding",
            approvalTier = ApprovalTier.SENIOR,
            failureCategory = FailureCategory.BANK_REJECTED,
            railAttempts = 2,
            bankReference = "BANK-HTTP-000042",
            usdEquivalentMinor = 185_000,
            reversalReference = "REV-HTTP-000042",
            history = listOf("VALIDATING: a", "FAILED: b"),
        )
        assertEquals(status, roundTrip(status))
    }

    @Test
    fun `every activity payload survives a round trip intact`() {
        val money = Money(25_000, "USD")
        assertEquals(
            ValidatePayoutRequest("po-1", "cust-1", money, Rail.MQ, Region.CN),
            roundTrip(ValidatePayoutRequest("po-1", "cust-1", money, Rail.MQ, Region.CN)),
        )
        assertEquals(ValidatePayoutResponse(true, "ok"), roundTrip(ValidatePayoutResponse(true, "ok")))
        assertEquals(ReserveFundsRequest("po-1", money), roundTrip(ReserveFundsRequest("po-1", money)))
        assertEquals(ReserveFundsResponse("RES-1"), roundTrip(ReserveFundsResponse("RES-1")))
        assertEquals(ReleaseFundsRequest("po-1", ""), roundTrip(ReleaseFundsRequest("po-1", "")))
        assertEquals(ReleaseFundsResponse(true), roundTrip(ReleaseFundsResponse(true)))
        assertEquals(MarkPayoutRequest("po-1", "FAILED", "x"), roundTrip(MarkPayoutRequest("po-1", "FAILED", "x")))
        assertEquals(MarkPayoutResponse(true), roundTrip(MarkPayoutResponse(true)))
        assertEquals(ValidateFxQuoteRequest("po-1", money), roundTrip(ValidateFxQuoteRequest("po-1", money)))
        assertEquals(
            ValidateFxQuoteResponse(0.74, 18_500, 1L),
            roundTrip(ValidateFxQuoteResponse(0.74, 18_500, 1L)),
        )
        assertEquals(
            SubmitToRailRequest("po-1", Rail.HTTP, Region.SG, money, "po-1-rail-1"),
            roundTrip(SubmitToRailRequest("po-1", Rail.HTTP, Region.SG, money, "po-1-rail-1")),
        )
        assertEquals(SubmitToRailResponse(true, "BANK-1", 3), roundTrip(SubmitToRailResponse(true, "BANK-1", 3)))
        assertEquals(
            ReverseRailRequest("po-1", "po-1-rail-1", Rail.HTTP, "BANK_REJECTED"),
            roundTrip(ReverseRailRequest("po-1", "po-1-rail-1", Rail.HTTP, "BANK_REJECTED")),
        )
        assertEquals(ReverseRailResponse(true, "REV-1"), roundTrip(ReverseRailResponse(true, "REV-1")))
        assertEquals(BankStatusProbeRequest("po-1", "BANK-1"), roundTrip(BankStatusProbeRequest("po-1", "BANK-1")))
        assertEquals(
            BankStatusProbeResponse(BankStatus.COMPLETED),
            roundTrip(BankStatusProbeResponse(BankStatus.COMPLETED)),
        )
        assertEquals(NotifyRequest("po-1", "customer", "done"), roundTrip(NotifyRequest("po-1", "customer", "done")))
        assertEquals(NotifyResponse(true), roundTrip(NotifyResponse(true)))
    }

    @Test
    fun `encodeDefaults keeps fields that are still holding their declared default`() {
        // kotlinx omits fields still holding their declared default unless told otherwise,
        // which drops approvalTier and failureCategory from the response.
        val minimal = PayoutStatusResponse(
            payoutId = "po-1",
            status = BusinessStatus.RECEIVED,
            currentStep = "received",
        )
        val encoded = json(minimal)
        assertContains(encoded, "\"approvalTier\":\"NONE\"")
        assertContains(encoded, "\"failureCategory\":\"NONE\"")
        assertContains(encoded, "\"railAttempts\":0")
        assertContains(encoded, "\"reversalReference\":\"\"")
        assertContains(encoded, "\"bankReference\":null")
    }

    @Test
    fun `enums serialise by name, so payloads stay readable in Temporal Web`() {
        val encoded = json(payoutRequestWithEnums())
        assertContains(encoded, "\"rail\":\"SFTP\"")
        assertContains(encoded, "\"region\":\"CN\"")
        // By name, not by ordinal. An ordinal round-trips but leaves the payload unreadable
        // in Temporal Web.
        assertFalse(encoded.contains("\"rail\":1"), "an ordinal would be unreadable in the UI")
        assertTrue(
            encoded.startsWith("{") && encoded.endsWith("}"),
            "the payload is plain JSON text, not an opaque encoding",
        )
    }

    @Test
    fun `serialisation is driven by the runtime class, never the declared type`() {
        // The value below is declared as Any; the converter still produces the
        // ProcessPayoutRequest encoding, because that is what it was handed at runtime. A
        // declared type that is a sealed parent, a generic, or null carries no usable runtime
        // type here, which is what the single-param rule exists for.
        val declaredAsAny: Any = payoutRequestWithEnums()
        val viaAny = json(declaredAsAny)
        val viaConcrete = json(payoutRequestWithEnums())
        assertEquals(viaConcrete, viaAny)
        assertContains(viaAny, "\"idempotencyKey\":\"po-000042-rail-1\"")
    }

    @Test
    fun `a type kotlinx cannot serialise falls through to the Jackson fallback`() {
        // SDK-internal types, java.time and the plain values in failure details take this
        // path. The payload must still be produced, and still be json/plain.
        val payload = converter.toData(LegacyPojo().apply { name = "ops"; count = 2 }).orElseThrow()
        assertEquals(
            "json/plain",
            payload.getMetadataOrThrow("encoding").toString(StandardCharsets.UTF_8),
        )
        val text = payload.data.toString(StandardCharsets.UTF_8)
        assertContains(text, "\"name\":\"ops\"")

        val back = converter.fromData(payload, LegacyPojo::class.java, LegacyPojo::class.java)
        assertEquals("ops", back.name)
        assertEquals(2, back.count)
    }

    @Test
    fun `a null value is handed to the fallback rather than crashing`() {
        // In the real chain NullPayloadConverter handles this first; in isolation the
        // fallback must still cope, because toData(null) cannot consult a runtime class.
        assertTrue(converter.toData(null).isPresent)
    }

    private fun payoutRequestWithEnums() = ProcessPayoutRequest(
        payoutId = "po-000042",
        customerId = "cust-0007",
        amount = Money(250_000, "SGD"),
        rail = Rail.SFTP,
        region = Region.CN,
        scenarioName = "converter-test",
        idempotencyKey = "po-000042-rail-1",
    )

    /** No @Serializable, a no-arg constructor: exactly what the Jackson fallback is for. */
    class LegacyPojo {
        var name: String = ""
        var count: Int = 0
    }
}
