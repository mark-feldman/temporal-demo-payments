package com.example.payouts.model.workflow

import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.FailureCategory
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import kotlinx.serialization.Serializable

/**
 * Single-param request/response on every boundary. The kotlinx PayloadConverter only sees the
 * runtime class when serialising, so every top-level type must be a concrete @Serializable
 * data class: no sealed types and no bare generics.
 */
@Serializable
data class ProcessPayoutRequest(
    val payoutId: String,
    val customerId: String,
    val amount: Money,
    val rail: Rail,
    val region: Region,
    val scenarioName: String,
    val idempotencyKey: String,
)

@Serializable
data class ProcessPayoutResponse(
    val payoutId: String,
    val status: BusinessStatus,
    val failureCategory: FailureCategory = FailureCategory.NONE,
    val bankReference: String? = null,
    val railAttempts: Int = 0,
    val message: String = "",
)

@Serializable
data class ApprovalDecisionRequest(
    val approved: Boolean,
    val approver: String,
    val note: String = "",
)

@Serializable
data class BankStatusUpdateRequest(
    val status: BankStatus,
    val bankReference: String = "",
)

/** Returned by the Query. The UI polls this; it is Temporal's state, not a cached copy. */
@Serializable
data class PayoutStatusResponse(
    val payoutId: String,
    val status: BusinessStatus,
    val currentStep: String,
    val approvalTier: ApprovalTier = ApprovalTier.NONE,
    val failureCategory: FailureCategory = FailureCategory.NONE,
    val railAttempts: Int = 0,
    val bankReference: String? = null,
    val usdEquivalentMinor: Long = 0,
    /** Set when the saga reversed an instruction the bank had already accepted. */
    val reversalReference: String = "",
    val history: List<String> = emptyList(),
)
