package com.example.payouts.model.domain

import kotlinx.serialization.Serializable

enum class Rail { HTTP, SFTP, MQ }

enum class Region { SG, CN, AU }

/**
 * The eighteen canonical states. Eight of them form the happy path.
 *
 * (Seventeen before `SETTLING_WITH_BANK`. The count in this comment said "sixteen" while the
 * enum held seventeen, and `backend/contract/demo-backend-contract.md` repeated it -- both are
 * corrected here.)
 */
enum class BusinessStatus {
    RECEIVED,
    VALIDATING,
    VALIDATED,
    FUNDS_RESERVED,
    FX_QUOTE_VALIDATED,
    AWAITING_APPROVAL,
    APPROVED,
    SUBMITTING_TO_BANK,
    SUBMITTED_TO_BANK,
    /** Low-value: the rail answers inline, so there is no callback to wait for. */
    SETTLING_WITH_BANK,
    AWAITING_BANK_CONFIRMATION,
    POLLING_BANK_STATUS,
    COMPLETED,
    FAILED,
    CANCELLED,
    COMPENSATING,
    COMPENSATED,
    UNKNOWN_BANK_STATUS,
}

enum class ApprovalTier { NONE, L1, SENIOR }

enum class BankStatus { ACCEPTED, COMPLETED, REJECTED, UNKNOWN }

enum class FailureCategory {
    NONE,
    VALIDATION,
    INSUFFICIENT_FUNDS,
    FX_QUOTE_EXPIRED,
    RAIL_TRANSIENT,
    RAIL_PERMANENT,
    BANK_REJECTED,
    APPROVAL_TIMEOUT,
    /**
     * A human looked at it and said no -- distinct from APPROVAL_TIMEOUT, where nobody
     * looked. Recorded as VALIDATION until the simulator started declining 20% of approvals,
     * at which point a fifth of every run showed up on the dashboard as a validation failure
     * that had nothing to do with validation.
     */
    APPROVAL_DECLINED,
    UNKNOWN_BANK_STATUS,
}

/** Money is always integer minor units. Never a float, never a BigDecimal on the wire. */
@Serializable
data class Money(val amountMinor: Long, val currency: String) {
    override fun toString() = "%,.2f %s".format(amountMinor / 100.0, currency)
}

/**
 * Approval thresholds, in USD minor units. Non-USD payouts are converted by the FX
 * quote first, so the boundary is evaluated against a single currency.
 */
/**
 * Where the bank answers inline instead of calling back.
 *
 * A low-value payout is settled by a single synchronous call to the rail: the instruction is
 * submitted and the answer comes back in the same activity, so there is nothing to wait for
 * and no 45s deadline to burn. Above the threshold the instruction is confirmed out of band,
 * which is the durable-wait-on-a-signal path -- and the one worth demonstrating.
 *
 * Read from the workflow's own input, never from configuration: the branch has to replay
 * identically, and `request.amount` is recorded in WorkflowExecutionStarted. Deciding it from
 * `ScenarioStore` would be a non-determinism bug.
 *
 * The two bands nest inside the approval bands rather than cutting across them:
 *
 *     < $100      settles inline, no human
 *     $100-$500   callback, no human
 *     > $500      callback, and a human (see [ApprovalThresholds])
 *
 * $100 sits deliberately BELOW the $250 the demo's manual scenarios start with, so scenarios
 * 1, 2, 3 and 5 keep their durable bank wait and the Bank buttons in the UI still have
 * something to signal. `DemoScenarioDefaultsTest` holds them above it.
 */
object SettlementThresholds {
    const val SYNC_BELOW_MINOR = 10_000L // $100.00

    /** True when the rail answers in the same activity call, so no callback is expected. */
    fun settlesSynchronously(amountMinor: Long): Boolean = amountMinor < SYNC_BELOW_MINOR
}

object ApprovalThresholds {
    const val L1_FROM_MINOR = 50_000L      // $500.00
    const val SENIOR_FROM_MINOR = 100_000L // $1,000.00

    fun tierFor(usdEquivalentMinor: Long): ApprovalTier = when {
        usdEquivalentMinor < L1_FROM_MINOR -> ApprovalTier.NONE
        usdEquivalentMinor <= SENIOR_FROM_MINOR -> ApprovalTier.L1
        else -> ApprovalTier.SENIOR
    }

    fun bandFor(usdEquivalentMinor: Long): String = when (tierFor(usdEquivalentMinor)) {
        ApprovalTier.NONE -> "low"
        ApprovalTier.L1 -> "approval"
        ApprovalTier.SENIOR -> "high-value"
    }
}
