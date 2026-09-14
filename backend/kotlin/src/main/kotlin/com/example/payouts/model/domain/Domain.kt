package com.example.payouts.model.domain

import kotlinx.serialization.Serializable

enum class Rail { HTTP, SFTP, MQ }

enum class Region { SG, CN, AU }

/** The eighteen canonical states. Eight of them form the happy path. */
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
    /** Low-value: the rail answers inline, with no callback to wait for. */
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
    /** An approver refused the payout. Distinct from APPROVAL_TIMEOUT, where none responded. */
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
 * Below the threshold a payout is settled by a single call to the rail, which returns the
 * answer in the same activity. At or above it, the instruction is confirmed out of band and
 * the workflow waits durably on the callback signal.
 *
 * The bands nest inside the approval bands:
 *
 *     < $100      settles inline, no approver
 *     $100-$500   callback, no approver
 *     > $500      callback and an approver (see [ApprovalThresholds])
 *
 * Read from the workflow's input, which is recorded in WorkflowExecutionStarted and so replays
 * identically. Reading it from `ScenarioStore` instead would be a non-determinism bug.
 *
 * The demo's manual scenarios all start above this threshold, so they keep their bank wait;
 * `DemoScenarioDefaultsTest` asserts that.
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
