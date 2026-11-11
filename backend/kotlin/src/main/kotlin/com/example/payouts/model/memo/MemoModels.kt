package com.example.payouts.model.memo

import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.PaymentOutcome
import com.example.payouts.model.domain.PaymentStage
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.domain.StageState
import kotlinx.serialization.Serializable

/**
 * Memo values for the streamed payment workflows.
 *
 * `upsertMemo` merges top-level keys only, so each key holds one whole object and every upsert
 * writes it in full. Each value is a concrete @Serializable class: the payload converter encodes
 * from the runtime class, and a bare List would fall back to Jackson.
 */
object MemoKeys {
    const val PAYMENT = "payment"
    const val PROGRESS = "progress"
    const val STAGES = "stages"
    const val STREAM = "stream"
    const val RELAY = "relay"
    const val NOTIFICATION = "notification"
    const val OUTCOME = "outcome"
}

/** Set at start by whoever starts the workflow. */
@Serializable
data class PaymentMemo(
    val payoutId: String,
    val amount: Money,
    val rail: Rail,
    val region: Region,
    /** Blank on A; A's workflow id on B. */
    val parentWorkflowId: String = "",
)

/** B: where processing is. A: the last update A accepted. */
@Serializable
data class ProgressMemo(
    val stage: PaymentStage? = null,
    val state: StageState? = null,
    val outcome: PaymentOutcome? = null,
    val seq: Int = -1,
    val updatedAt: Long = 0,
)

@Serializable
data class StageMemoEntry(
    val seq: Int,
    val stage: PaymentStage? = null,
    val state: StageState? = null,
    val outcome: PaymentOutcome? = null,
    val at: Long,
)

@Serializable
data class StagesMemo(val entries: List<StageMemoEntry> = emptyList())

enum class AckState { NOT_YET, AWAITING, RECEIVED, TIMED_OUT, CANCELLED }

/** B's view of its own stream. */
@Serializable
data class StreamMemo(
    val topics: List<String>,
    val statusEventsPublished: Int = 0,
    val finalSeq: Int? = null,
    val ackState: AckState = AckState.NOT_YET,
)

/** A's view of what the relay delivered. */
@Serializable
data class RelayMemo(
    val childWorkflowId: String = "",
    val childRunId: String = "",
    val received: Int = 0,
    val duplicatesDropped: Int = 0,
    val gaps: Int = 0,
    val lastOffset: Long = -1,
    val ackSent: Boolean = false,
)

@Serializable
data class NotificationMemo(
    val customerNotified: Boolean,
    val message: String,
    val at: Long,
)

@Serializable
data class OutcomeMemo(
    val outcome: PaymentOutcome,
    val failedStage: PaymentStage? = null,
    val reason: String = "",
    val settlementReference: String = "",
)
