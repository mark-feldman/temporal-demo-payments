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
 * The one mock that returns something the workflow reasons about: the approval
 * threshold is evaluated against usdEquivalentMinor, so non-USD payouts get a
 * single comparable number.
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
    /** Stable across every retry. That stability is the whole point of the Tab 2 demo. */
    val idempotencyKey: String,
)

@Serializable
data class SubmitToRailResponse(
    val accepted: Boolean,
    val bankReference: String,
    val attempt: Int,
)

// ---- bank status (mock callback source, used by the simulation runner) ----

@Serializable
data class BankStatusProbeRequest(val payoutId: String, val bankReference: String)

@Serializable
data class BankStatusProbeResponse(val status: BankStatus)

// ---- notification ----

@Serializable
data class NotifyRequest(val payoutId: String, val channel: String, val message: String)

@Serializable
data class NotifyResponse(val sent: Boolean)
