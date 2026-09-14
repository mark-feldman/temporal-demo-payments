package com.example.payouts.workflow

import com.example.payouts.activities.*
import com.example.payouts.app.RetryProfiles
import com.example.payouts.model.activity.*
import com.example.payouts.model.domain.*
import com.example.payouts.model.workflow.*
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.common.SearchAttributeKey
import io.temporal.failure.ActivityFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Saga
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/** Registered by @WorkflowImpl; deliberately NOT a Spring bean -- Temporal creates one per execution. */
@WorkflowImpl(taskQueues = [TASK_QUEUE])
class PayoutWorkflowImpl : PayoutWorkflow {

    private val log = Workflow.getLogger(javaClass)

    // ---- mutable workflow state, read by the Query ----
    private var status = BusinessStatus.RECEIVED
    private var step = "received"
    private var approvalTier = ApprovalTier.NONE
    private var failure = FailureCategory.NONE
    private var railAttempts = 0
    private var bankReference: String? = null
    private var usdEquivalentMinor = 0L
    private var payoutId = ""
    private val history = mutableListOf<String>()

    private var approval: ApprovalDecisionRequest? = null
    private var bankStatus: BankStatus? = null

    // ---- activity stubs. Options get the Kotlin DSL; the stub still takes a class literal. ----
    private val validation = Workflow.newActivityStub(
        ValidationActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(5))
            setScheduleToCloseTimeout(Duration.ofSeconds(30))
            setTaskQueue(TASK_QUEUE)
            setSummary("Validate payout request")
            setRetryOptions { setMaximumAttempts(3); setDoNotRetry("ValidationFailure") }
        },
    )

    private val ledger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(5))
            setScheduleToCloseTimeout(Duration.ofMinutes(1))
            setTaskQueue(TASK_QUEUE)
            setSummary("Ledger operation")
            setRetryOptions { setMaximumAttempts(5); setDoNotRetry("InsufficientFunds") }
        },
    )

    /** Compensation must not give up: attempts unset = unlimited, flat 5s backoff. */
    private val compensationLedger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(5))
            setScheduleToCloseTimeout(Duration.ofHours(1))
            setTaskQueue(TASK_QUEUE)
            setSummary("Release reserved funds")
            setRetryOptions {
                setInitialInterval(Duration.ofSeconds(5))
                setBackoffCoefficient(1.0)
            }
        },
    )

    private val fx = Workflow.newActivityStub(
        FxActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(5))
            setScheduleToCloseTimeout(Duration.ofSeconds(30))
            setTaskQueue(TASK_QUEUE)
            setSummary("Check FX quote validity")
            setRetryOptions { setMaximumAttempts(3); setDoNotRetry("FxQuoteExpired") }
        },
    )

    private val rail = Workflow.newActivityStub(
        RailActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(2))
            setTaskQueue(TASK_QUEUE)
            setSummary("Submit instruction to bank rail")
            setRetryOptions(RetryProfiles.FAST.toBuilder().setDoNotRetry("RailRejected", "BankRejected").build())
        },
    )

    /** Capped at 3 so a dead notifier cannot hold a payout in COMPENSATING forever. */
    private val notifier = Workflow.newActivityStub(
        NotificationActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(5))
            setScheduleToCloseTimeout(Duration.ofSeconds(30))
            setTaskQueue(TASK_QUEUE)
            setSummary("Notify customer or ops")
            setRetryOptions { setMaximumAttempts(3) }
        },
    )

    override fun processPayout(request: ProcessPayoutRequest): ProcessPayoutResponse {
        payoutId = request.payoutId
        upsertSearchAttributes(request)
        val saga = Saga { setContinueWithError(true) }

        return try {
            advance(BusinessStatus.VALIDATING, "Validating payout request")
            validation.validatePayout(
                ValidatePayoutRequest(
                    payoutId = request.payoutId,
                    customerId = request.customerId,
                    amount = request.amount,
                    rail = request.rail,
                    region = request.region,
                ),
            )
            advance(BusinessStatus.VALIDATED, "Request validated")

            val reservation = ledger.reserveFunds(ReserveFundsRequest(request.payoutId, request.amount))
            saga.addCompensation {
                compensationLedger.releaseReservedFunds(
                    ReleaseFundsRequest(request.payoutId, reservation.reservationId),
                )
            }
            advance(BusinessStatus.FUNDS_RESERVED, "Reserved ${request.amount}")

            val quote = fx.validateFxQuote(ValidateFxQuoteRequest(request.payoutId, request.amount))
            usdEquivalentMinor = quote.usdEquivalentMinor
            advance(BusinessStatus.FX_QUOTE_VALIDATED, "FX quote valid, USD equivalent recorded")

            approvalTier = ApprovalThresholds.tierFor(usdEquivalentMinor)
            if (approvalTier != ApprovalTier.NONE && !awaitApproval(request)) {
                // Cancelled: compensation runs in the catch below via the same saga.
                throw ApprovalDeclined()
            }

            // Rail selection is plain deterministic workflow code: no I/O, so no Activity.
            // Visibility comes from setCurrentDetails, not from an event.
            advance(BusinessStatus.SUBMITTING_TO_BANK, "Selected ${request.rail} rail for ${request.region}")
            val submission = rail.submitToRail(
                SubmitToRailRequest(
                    payoutId = request.payoutId,
                    rail = request.rail,
                    region = request.region,
                    amount = request.amount,
                    idempotencyKey = request.idempotencyKey,
                ),
            )
            railAttempts = submission.attempt
            bankReference = submission.bankReference
            advance(BusinessStatus.SUBMITTED_TO_BANK, "Bank accepted instruction ${submission.bankReference}")

            when (awaitBankStatus()) {
                BankStatus.COMPLETED -> complete(request)
                BankStatus.REJECTED -> {
                    failure = FailureCategory.BANK_REJECTED
                    throw BankRejected()
                }
                // The bank accepted an instruction and never confirmed. Releasing the
                // reservation could pay out twice. Mark, make searchable, route to ops --
                // do NOT compensate.
                BankStatus.UNKNOWN, BankStatus.ACCEPTED -> unknownStatus(request)
            }
        } catch (e: Exception) {
            handleFailure(request, saga, e)
        }
    }

    // ---- signals and query ----

    override fun approve(request: ApprovalDecisionRequest) {
        approval = request
    }

    override fun bankStatusUpdate(request: BankStatusUpdateRequest) {
        bankStatus = request.status
        if (request.bankReference.isNotBlank()) bankReference = request.bankReference
    }

    override fun currentStatus() = PayoutStatusResponse(
        payoutId = payoutId,
        status = status,
        currentStep = step,
        approvalTier = approvalTier,
        failureCategory = failure,
        railAttempts = railAttempts,
        bankReference = bankReference,
        usdEquivalentMinor = usdEquivalentMinor,
        history = history.toList(),
    )

    // ---- internals ----

    private fun awaitApproval(request: ProcessPayoutRequest): Boolean {
        advance(BusinessStatus.AWAITING_APPROVAL, "Waiting for $approvalTier approval")
        val timeout = Duration.ofSeconds(approvalTimeoutSeconds())
        val decided = Workflow.await(timeout) { approval != null }
        return when {
            !decided -> {
                failure = FailureCategory.APPROVAL_TIMEOUT
                false
            }
            approval?.approved == true -> {
                advance(BusinessStatus.APPROVED, "Approved by ${approval?.approver}")
                true
            }
            else -> {
                failure = FailureCategory.VALIDATION
                false
            }
        }
    }

    private fun awaitBankStatus(): BankStatus {
        advance(BusinessStatus.AWAITING_BANK_CONFIRMATION, "Waiting for bank payment status")
        val arrived = Workflow.await(Duration.ofSeconds(bankCallbackTimeoutSeconds())) { bankStatus != null }
        return if (arrived) bankStatus ?: BankStatus.UNKNOWN else BankStatus.UNKNOWN
    }

    private fun complete(request: ProcessPayoutRequest): ProcessPayoutResponse {
        ledger.markPayout(MarkPayoutRequest(request.payoutId, "COMPLETED"))
        notifier.notify(NotifyRequest(request.payoutId, "customer", "Payout completed"))
        advance(BusinessStatus.COMPLETED, "Payout completed")
        return ProcessPayoutResponse(
            payoutId = request.payoutId,
            status = BusinessStatus.COMPLETED,
            bankReference = bankReference,
            railAttempts = railAttempts,
            message = "completed",
        )
    }

    private fun unknownStatus(request: ProcessPayoutRequest): ProcessPayoutResponse {
        failure = FailureCategory.UNKNOWN_BANK_STATUS
        advance(BusinessStatus.UNKNOWN_BANK_STATUS, "Bank accepted but never confirmed - ops review required")
        notifier.notify(NotifyRequest(request.payoutId, "ops", "Payment status unknown, needs investigation"))
        return ProcessPayoutResponse(
            payoutId = request.payoutId,
            status = BusinessStatus.UNKNOWN_BANK_STATUS,
            failureCategory = FailureCategory.UNKNOWN_BANK_STATUS,
            bankReference = bankReference,
            railAttempts = railAttempts,
            message = "unknown bank status - funds deliberately NOT released",
        )
    }

    private fun handleFailure(
        request: ProcessPayoutRequest,
        saga: Saga,
        e: Exception,
    ): ProcessPayoutResponse {
        if (failure == FailureCategory.NONE) {
            failure = when {
                e is ActivityFailure && e.cause?.message?.contains("Rail") == true -> FailureCategory.RAIL_PERMANENT
                else -> FailureCategory.VALIDATION
            }
        }
        val cancelled = failure == FailureCategory.APPROVAL_TIMEOUT || e is ApprovalDeclined
        advance(BusinessStatus.COMPENSATING, "Unwinding: ${failure.name}")

        // Compensation must survive the cancellation that triggered it.
        Workflow.newDetachedCancellationScope {
            saga.compensate()
            ledger.markPayout(
                MarkPayoutRequest(request.payoutId, if (cancelled) "CANCELLED" else "FAILED", failure.name),
            )
            notifier.notify(NotifyRequest(request.payoutId, "customer", "Payout ${failure.name}"))
        }.run()

        val terminal = if (cancelled) BusinessStatus.CANCELLED else BusinessStatus.FAILED
        advance(BusinessStatus.COMPENSATED, "Reserved funds released")
        advance(terminal, "Payout ${terminal.name.lowercase()}")
        return ProcessPayoutResponse(
            payoutId = request.payoutId,
            status = terminal,
            failureCategory = failure,
            railAttempts = railAttempts,
            message = e.message ?: failure.name,
        )
    }

    private fun advance(next: BusinessStatus, detail: String) {
        status = next
        step = detail
        history += "${next.name}: $detail"
        Workflow.setCurrentDetails("**${next.name}** - $detail")
        Workflow.upsertTypedSearchAttributes(BUSINESS_STATUS.valueSet(next.name))
        log.info("{} -> {}", next, detail)
    }

    private fun upsertSearchAttributes(request: ProcessPayoutRequest) {
        Workflow.upsertTypedSearchAttributes(
            PAYOUT_ID.valueSet(request.payoutId),
            CUSTOMER_ID.valueSet(request.customerId),
            RAIL.valueSet(request.rail.name),
            REGION.valueSet(request.region.name),
            AMOUNT_MINOR.valueSet(request.amount.amountMinor),
            CURRENCY.valueSet(request.amount.currency),
            SCENARIO_NAME.valueSet(request.scenarioName),
            BUSINESS_STATUS.valueSet(status.name),
        )
    }

    private fun approvalTimeoutSeconds() = Workflow.getInfo().searchAttributes.let { 30L }

    private fun bankCallbackTimeoutSeconds() = 45L

    private class ApprovalDeclined : RuntimeException("approval not granted")
    private class BankRejected : RuntimeException("bank rejected the payment")

    private companion object {
        val PAYOUT_ID = SearchAttributeKey.forKeyword("payoutId")
        val CUSTOMER_ID = SearchAttributeKey.forKeyword("customerId")
        val RAIL = SearchAttributeKey.forKeyword("rail")
        val REGION = SearchAttributeKey.forKeyword("region")
        val AMOUNT_MINOR = SearchAttributeKey.forLong("amountMinor")
        val CURRENCY = SearchAttributeKey.forKeyword("currency")
        val BUSINESS_STATUS = SearchAttributeKey.forKeyword("businessStatus")
        val SCENARIO_NAME = SearchAttributeKey.forKeyword("scenarioName")
    }
}
