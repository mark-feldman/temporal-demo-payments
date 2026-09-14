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
    private var reversalReference = ""
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

    /** Reversal must not give up either: attempts unset, flat 5s. */
    private val compensationRail = Workflow.newActivityStub(
        RailActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofHours(1))
            setTaskQueue(TASK_QUEUE)
            setSummary("Reverse bank instruction")
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

    /**
     * The retry policy here is the polling loop: `pollBankStatus` throws a retryable failure
     * while the bank says "pending", so Temporal re-invokes it on a schedule. Eight attempts
     * at a 2s flat interval is roughly 16 seconds of asking before we accept that the bank is
     * not going to answer.
     *
     * Note what this does NOT produce: intermediate attempts are not written to Event History.
     * One ActivityTaskScheduled, then ActivityTaskStarted carrying the final attempt number.
     * The polls are observable while they are happening, through the pending-activity record
     * on DescribeWorkflowExecution, and afterwards only as that attempt count plus the elapsed
     * time between Scheduled and Started.
     */
    private val bank = Workflow.newActivityStub(
        BankActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(5))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Poll bank for payment status")
            setRetryOptions {
                setInitialInterval(Duration.ofSeconds(2))
                setBackoffCoefficient(1.0)
                setMaximumAttempts(8)
            }
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

            // Registered BEFORE the call, not after. If reserveFunds moves money in the ledger
            // and then dies before returning, a compensation registered afterwards would never
            // exist and the funds would stay reserved. Releasing by payoutId rather than the
            // reservation id is what makes that possible -- we cannot reference a result we do
            // not have yet.
            saga.addCompensation {
                compensationLedger.releaseReservedFunds(ReleaseFundsRequest(request.payoutId, ""))
            }
            ledger.reserveFunds(ReserveFundsRequest(request.payoutId, request.amount))
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
            // Registered before the call for the same reason as the ledger release: if
            // submitToRail dies after the bank accepted the instruction, we still have to be
            // able to reverse it. Keyed on the idempotency key, which is known up front --
            // the bank reference is not.
            saga.addCompensation {
                reversalReference = compensationRail.reverseRailInstruction(
                    ReverseRailRequest(
                        payoutId = request.payoutId,
                        idempotencyKey = request.idempotencyKey,
                        rail = request.rail,
                        reason = failure.name,
                    ),
                ).reversalReference
            }
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

            val reported = when (val first = awaitBankStatus()) {
                // No answer, or an ambiguous one. Ask the bank directly rather than
                // escalating to a human: the retry policy on the poll stub does the polling.
                BankStatus.UNKNOWN, BankStatus.ACCEPTED -> pollBankUntilResolved(request)
                else -> first
            }
            when (reported) {
                BankStatus.COMPLETED -> complete(request)
                BankStatus.REJECTED -> {
                    failure = FailureCategory.BANK_REJECTED
                    throw BankRejected()
                }
                // Polling exhausted its retries without the bank ever answering.
                BankStatus.UNKNOWN, BankStatus.ACCEPTED -> throw BankStatusUnresolved()
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
        reversalReference = reversalReference,
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

    /**
     * Poll the bank for the real status of an instruction it accepted but never confirmed.
     * Returns UNKNOWN if the retry policy is exhausted without an answer.
     */
    private fun pollBankUntilResolved(request: ProcessPayoutRequest): BankStatus {
        advance(BusinessStatus.POLLING_BANK_STATUS, "No callback - polling the bank for status")
        return runCatching {
            bank.pollBankStatus(
                BankStatusProbeRequest(request.payoutId, bankReference.orEmpty()),
            ).status
        }.getOrElse {
            log.warn("bank polling exhausted for {}", request.payoutId)
            BankStatus.UNKNOWN
        }
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

    private fun handleFailure(
        request: ProcessPayoutRequest,
        saga: Saga,
        e: Exception,
    ): ProcessPayoutResponse {
        if (failure == FailureCategory.NONE) {
            failure = when {
                e is BankStatusUnresolved -> FailureCategory.UNKNOWN_BANK_STATUS
                e is ActivityFailure && e.cause?.message?.contains("Rail") == true -> FailureCategory.RAIL_PERMANENT
                else -> FailureCategory.VALIDATION
            }
        }
        val cancelled = failure == FailureCategory.APPROVAL_TIMEOUT || e is ApprovalDeclined
        advance(BusinessStatus.COMPENSATING, "Unwinding: ${failure.name}")

        // Compensation must survive the cancellation that triggered it.
        Workflow.newDetachedCancellationScope {
            // Unwinds in reverse registration order: reverse the bank instruction first,
            // then release the funds we reserved.
            saga.compensate()
            advance(BusinessStatus.COMPENSATED, "Bank instruction reversed and reserved funds released")
            ledger.markPayout(
                MarkPayoutRequest(request.payoutId, if (cancelled) "CANCELLED" else "FAILED", failure.name),
            )
            notifier.notify(
                NotifyRequest(request.payoutId, "customer", "Payout ${failure.name} - funds returned"),
            )
        }.run()

        val terminal = if (cancelled) BusinessStatus.CANCELLED else BusinessStatus.FAILED
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

    /**
     * The bank accepted the instruction and never confirmed, and polling could not get an
     * answer either. Compensating here is a POLICY CHOICE, not a safe default: if the
     * instruction did settle at the bank, releasing the reservation pays out twice. It is
     * defensible only because every automated avenue has been exhausted first -- which is
     * exactly what the polling above is for.
     */
    private class BankStatusUnresolved :
        RuntimeException("bank never confirmed and polling was exhausted")

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
