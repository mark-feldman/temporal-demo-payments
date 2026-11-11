package com.example.payouts.model.workflow

import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.PaymentOutcome
import com.example.payouts.model.domain.PaymentStage
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.stream.StageEvent
import kotlinx.serialization.Serializable

// ---- PaymentRequestWorkflow (A, the initiator) ----

@Serializable
data class StartStreamedPaymentRequest(
    val payoutId: String,
    val customerId: String,
    val amount: Money,
    val rail: Rail,
    val region: Region,
    val scenarioName: String,
)

@Serializable
data class StreamedPaymentResponse(
    val payoutId: String,
    val outcome: PaymentOutcome,
    val failedStage: PaymentStage? = null,
    val reason: String = "",
    val settlementReference: String = "",
    /** Status updates A accepted. `finalSeq + 1` when nothing was missed. */
    val statusUpdatesReceived: Int,
    val gaps: Int,
)

/** One status event, relayed from B's stream. [offset] is its position in that stream. */
@Serializable
data class StageUpdateSignal(
    val offset: Long,
    val event: StageEvent,
)

/** B's outcome when it was cancelled, which it cannot return as a result. */
@Serializable
data class ProcessingCancelledSignal(
    val outcome: PaymentOutcome,
    val failedStage: PaymentStage? = null,
    val reason: String = "",
    val settlementReference: String = "",
)

// ---- PaymentProcessingWorkflow (B, the stream host) ----

@Serializable
data class ProcessPaymentRequest(
    val payoutId: String,
    val amount: Money,
    val rail: Rail,
    val region: Region,
    val parentWorkflowId: String,
)

@Serializable
data class ProcessPaymentResponse(
    val payoutId: String,
    val outcome: PaymentOutcome,
    val failedStage: PaymentStage? = null,
    val reason: String = "",
    val settlementReference: String = "",
    /** The `seq` of the OUTCOME status event. */
    val finalSeq: Int,
    val acknowledged: Boolean,
)

/** Sent by A once it holds every status update from seq 0 to [finalSeq]. */
@Serializable
data class StatusAcknowledgement(
    val finalSeq: Int,
)
