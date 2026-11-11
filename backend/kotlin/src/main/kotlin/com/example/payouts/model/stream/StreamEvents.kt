package com.example.payouts.model.stream

import com.example.payouts.model.domain.PaymentOutcome
import com.example.payouts.model.domain.PaymentStage
import com.example.payouts.model.domain.StageState
import kotlinx.serialization.Serializable

/**
 * Items on the Workflow Stream hosted by `PaymentProcessingWorkflow`. Each topic carries one
 * type, so a subscriber decodes by `item.topic`.
 *
 *  - [STATUS]: [StageEvent], published from workflow code. Deterministic, so activity retries
 *    never duplicate it. The relay forwards this topic, and only this topic, to the initiator.
 *  - [PROGRESS]: [ProgressEvent], published by stage activities. A failed attempt's events stay
 *    in the stream.
 *  - [RETRY]: [RetryEvent], force-flushed by a stage activity on attempt 2 and later, so a
 *    subscriber can strike out the earlier attempt's progress.
 */
object Topics {
    const val STATUS = "status"
    const val PROGRESS = "progress"
    const val RETRY = "retry"

    val ALL = listOf(STATUS, PROGRESS, RETRY)
}

enum class StageEventKind {
    /** A stage started, passed or failed. */
    STAGE,

    /** The terminal event. Its `seq` is the last one B publishes. */
    OUTCOME,
}

/**
 * A status event. Flat with an enum discriminator rather than a sealed type: the payload
 * converter sees only the runtime class.
 *
 * `seq` counts status events only, from 0 with no gaps. Stream offsets are global across topics,
 * so progress and retry items leave holes in the offsets; `seq` is what the initiator checks for
 * completeness.
 */
@Serializable
data class StageEvent(
    val seq: Int,
    val kind: StageEventKind,
    val stage: PaymentStage? = null,
    val state: StageState? = null,
    val outcome: PaymentOutcome? = null,
    val detail: String = "",
    /** Workflow time, epoch ms. */
    val at: Long,
)

@Serializable
data class ProgressEvent(
    val stage: PaymentStage,
    val attempt: Int,
    val step: Int,
    val of: Int,
    val detail: String,
)

@Serializable
data class RetryEvent(
    val stage: PaymentStage,
    val attempt: Int,
    val detail: String,
)
