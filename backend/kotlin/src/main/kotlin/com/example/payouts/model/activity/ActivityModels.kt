package com.example.payouts.model.activity

import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import kotlinx.serialization.Serializable

// ---- validation ----

@Serializable
data class ValidatePayoutRequest(
    val payoutId: String,
    val customerId: String,
    val amount: Money,
    val rail: Rail,
    val region: Region,
)

@Serializable
data class ValidatePayoutResponse(val valid: Boolean, val detail: String = "")

// ---- ledger ----

@Serializable
data class ReserveFundsRequest(val payoutId: String, val amount: Money)

@Serializable
data class ReserveFundsResponse(val reservationId: String)

@Serializable
data class ReleaseFundsRequest(val payoutId: String, val reservationId: String)

@Serializable
data class ReleaseFundsResponse(val released: Boolean)

@Serializable
data class MarkPayoutRequest(val payoutId: String, val outcome: String, val reason: String = "")

@Serializable
data class MarkPayoutResponse(val recorded: Boolean)

// ---- fx ----

@Serializable
data class ValidateFxQuoteRequest(val payoutId: String, val amount: Money)

/**
 * The approval threshold is evaluated against usdEquivalentMinor, so payouts in different
 * currencies are compared as one number.
 */
@Serializable
data class ValidateFxQuoteResponse(
    val rate: Double,
    val usdEquivalentMinor: Long,
    val expiresAtEpochMs: Long,
)

// ---- rail ----

@Serializable
data class SubmitToRailRequest(
    val payoutId: String,
    val rail: Rail,
    val region: Region,
    val amount: Money,
    /** Stable across every retry of the rail submission. */
    val idempotencyKey: String,
)

@Serializable
data class SubmitToRailResponse(
    val accepted: Boolean,
    val bankReference: String,
    val attempt: Int,
)

/**
 * Asks the bank to reverse an instruction it already accepted. Keyed on the idempotency
 * key rather than the bank reference, because the compensation is registered before
 * submitToRail runs -- there is no reference to quote yet.
 */
@Serializable
data class ReverseRailRequest(
    val payoutId: String,
    val idempotencyKey: String,
    val rail: Rail,
    val reason: String,
)

@Serializable
data class ReverseRailResponse(val reversed: Boolean, val reversalReference: String)

// ---- bank status ----

@Serializable
data class BankStatusProbeRequest(val payoutId: String, val bankReference: String)

@Serializable
data class BankStatusProbeResponse(val status: BankStatus)

@Serializable
data class BankSettlementRequest(
    val payoutId: String,
    val bankReference: String,
    val idempotencyKey: String,
)

/** The bank's inline answer: always COMPLETED or REJECTED, never ACCEPTED or UNKNOWN. */
@Serializable
data class BankSettlementResponse(val status: BankStatus)

// ---- notification ----

@Serializable
data class NotifyRequest(val payoutId: String, val channel: String, val message: String)

@Serializable
data class NotifyResponse(val sent: Boolean)
