package com.example.payouts.model.activity

import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.PaymentStage
import kotlinx.serialization.Serializable

// ---- payment stages (scenario 6) ----

/** One shape for every stage activity; [stage] says which. */
@Serializable
data class StageRequest(
    val payoutId: String,
    val stage: PaymentStage,
    val amount: Money,
)

@Serializable
data class StageResponse(
    val stage: PaymentStage,
    val reference: String,
    val attempt: Int,
)

// ---- stream relay ----

@Serializable
data class RelayRequest(
    /** The workflow hosting the stream, B. */
    val processingWorkflowId: String,
)

@Serializable
data class RelayResponse(
    /** The offset of the last item signalled to A, or -1 if none. */
    val lastOffset: Long,
    /** Items signalled by this attempt. */
    val relayed: Int,
    /** False when B closed before the relay saw the OUTCOME event. */
    val sawOutcome: Boolean,
)
