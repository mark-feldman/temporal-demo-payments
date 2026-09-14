package com.example.payouts.workflow

import com.example.payouts.activities.*
import com.example.payouts.model.activity.*
import com.example.payouts.model.domain.*
import com.example.payouts.model.workflow.*
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.common.SearchAttributeKey
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.spring.boot.WorkflowImpl
import io.temporal.workflow.Saga
import io.temporal.common.RetryOptions
import io.temporal.workflow.TimerOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * The attempt cap on the demo retry policy. Each workflow instance draws a value from this
 * range, so no two executions retry the same number of times.
 *
 * A failure-injection count at or above [DEMO_MIN_ATTEMPTS] exhausts the policy on some
 * executions and not others. `app.js` picks those counts and `DemoScenarioDefaultsTest` holds
 * them below this bound.
 */
const val DEMO_MIN_ATTEMPTS = 7
const val DEMO_MAX_ATTEMPTS = 10

/** Registered by @WorkflowImpl, not as a Spring bean: Temporal creates one per execution. */
@WorkflowImpl(taskQueues = [TASK_QUEUE])
class PayoutWorkflowImpl : PayoutWorkflow {

    private val log = Workflow.getLogger(javaClass)

    /**
     * Replay-safe RNG: seeded per workflow instance and replays identically. Workflow context
     * is available during construction, which the activity stubs below also rely on.
     */
    private val rng: java.util.Random = Workflow.newRandom()

    /** [DEMO_MIN_ATTEMPTS]-[DEMO_MAX_ATTEMPTS] attempts, 1s initial, x1.1, capped at 20s. */
    private fun demoRetry(): io.temporal.common.RetryOptions = RetryOptions {
        setInitialInterval(Duration.ofSeconds(1))
        setBackoffCoefficient(1.1)
        setMaximumInterval(Duration.ofSeconds(20))
        setMaximumAttempts(DEMO_MIN_ATTEMPTS + rng.nextInt(DEMO_MAX_ATTEMPTS - DEMO_MIN_ATTEMPTS + 1))
    }

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
    // Deadline flags flipped by timer callbacks. Callbacks and signal handlers run in event
    // order, so whether the signal beat the deadline is decided by which of the two ran first.
    /** Set once, at the top of processPayout. See [FINDABLE_STATUSES]. */
    private var visibilityMilestonesOnly = false
    private var approvalDeadlinePassed = false
    private var bankDeadlinePassed = false
    private val history = mutableListOf<String>()

    private var approval: ApprovalDecisionRequest? = null
    private var bankStatus: BankStatus? = null

    // ---- activity stubs. Options get the Kotlin DSL; the stub still takes a class literal. ----
    private val validation = Workflow.newActivityStub(
        ValidationActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Validate payout request")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("ValidationFailure").build())
        },
    )

    /** Reserves the funds. A separate stub from [ledgerOutcome] so each carries its own summary. */
    private val ledger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Reserve funds in the ledger")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("InsufficientFunds").build())
        },
    )

    /** Records the terminal outcome. Same options as [ledger]; only the summary differs. */
    private val ledgerOutcome = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Record the payout outcome in the ledger")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("InsufficientFunds").build())
        },
    )

    /** Compensation has no attempt cap: attempts unset means unlimited. Flat 5s backoff. */
    private val compensationLedger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofHours(1))
            setTaskQueue(TASK_QUEUE)
            setSummary("Release reserved funds")
            setRetryOptions {
                setInitialInterval(Duration.ofSeconds(5))
                setBackoffCoefficient(1.0)
            }
        },
    )

    /** Reversal has no attempt cap either: attempts unset, flat 5s. */
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
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Check FX quote validity")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("FxQuoteExpired").build())
        },
    )

    private val rail = Workflow.newActivityStub(
        RailActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Submit instruction to bank rail")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("RailRejected", "BankRejected").build())
        },
    )

    /**
     * The retry policy is the polling loop: `pollBankStatus` throws a retryable failure while
     * the bank reports "pending", so Temporal re-invokes it on a schedule.
     *
     * Intermediate attempts are not written to Event History -- one ActivityTaskScheduled, then
     * ActivityTaskStarted carrying the final attempt number. Polls in progress are visible on
     * the pending-activity record from DescribeWorkflowExecution; afterwards only the attempt
     * count and the Scheduled-to-Started gap remain.
     */
    private val bank = Workflow.newActivityStub(
        BankActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Poll bank for payment status")
            setRetryOptions(demoRetry())
        },
    )

    /**
     * Inline settlement for a low-value instruction, on its own stub: `bank` retries in order
     * to poll, whereas a settlement returns its answer as a value. `BankRejected` is
     * non-retryable here, so an injected refusal at this step fails once.
     */
    private val bankSettlement = Workflow.newActivityStub(
        BankActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Settle low-value instruction with the bank")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("BankRejected").build())
        },
    )

    /** Capped, so a dead notifier cannot hold a payout in COMPENSATING indefinitely. */
    private val notifier = Workflow.newActivityStub(
        NotificationActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Notify customer or ops")
            setRetryOptions(demoRetry())
        },
    )

    override fun processPayout(request: ProcessPayoutRequest): ProcessPayoutResponse {
        payoutId = request.payoutId
        // Resolved before the first command, so the decision is stable for the whole
        // execution. Patched: which upserts are emitted is part of the command stream.
        visibilityMilestonesOnly =
            Workflow.getVersion(VISIBILITY_MILESTONES_CHANGE, Workflow.DEFAULT_VERSION, VISIBILITY_MILESTONES_VERSION) >=
                VISIBILITY_MILESTONES_VERSION
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

            // Registered before the call it undoes, so it exists even if reserveFunds moves
            // money and then fails before returning. Keyed on payoutId, which is known now;
            // the reservation id is not.
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

            // Rail selection is deterministic workflow code, not an Activity: no I/O. It is
            // visible through setCurrentDetails rather than a history event.
            advance(BusinessStatus.SUBMITTING_TO_BANK, "Selected ${request.rail} rail for ${request.region}")
            // Registered before the call it undoes, so the instruction can be reversed even
            // if submitToRail fails after the bank accepted it. Keyed on the idempotency key,
            // which is known now; the bank reference is not.
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

            // How the bank confirms depends on the amount, read from the workflow's input so
            // it replays identically. Low-value instructions are answered inline by a single
            // activity; larger ones wait durably on the callback signal.
            val reported = if (settlesInline(request)) {
                settleWithBankInline(request)
            } else {
                when (val first = awaitBankStatus()) {
                    // No answer, or an ambiguous one: ask the bank directly. The retry policy
                    // on the poll stub does the polling.
                    BankStatus.UNKNOWN, BankStatus.ACCEPTED -> pollBankUntilResolved(request)
                    else -> first
                }
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
        // A decision arriving after the deadline timer fired is ignored.
        if (!approvalDeadlinePassed) approval = request
    }

    override fun bankStatusUpdate(request: BankStatusUpdateRequest) {
        if (bankDeadlinePassed) return
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
        val summary = "Waiting for $approvalTier approval"
        advance(BusinessStatus.AWAITING_APPROVAL, summary)
        // An explicit timer, not await's built-in timeout: the signal and the timer are
        // ordered by which callback ran first, which replays identically. The timer is owned
        // by a cancellation scope because a bare newTimer is not cancelled by await, and an
        // uncancelled one stays in history as an unresolved TimerStarted.
        val deadline = Workflow.newCancellationScope(Runnable {
            Workflow.newTimer(
                Duration.ofSeconds(approvalTimeoutSeconds()),
                TimerOptions.newBuilder().setSummary(summary).build(),
            )
                .thenApply { approvalDeadlinePassed = true }
        })
        deadline.run()
        Workflow.await { approval != null || approvalDeadlinePassed }
        deadline.cancel("approval decided")

        val decision = approval
        return when {
            decision == null -> {
                failure = FailureCategory.APPROVAL_TIMEOUT
                false
            }
            decision?.approved == true -> {
                advance(BusinessStatus.APPROVED, "Approved by ${decision.approver}")
                true
            }
            else -> {
                failure = FailureCategory.APPROVAL_DECLINED
                false
            }
        }
    }

    /**
     * Whether this payout takes the inline-settlement path.
     *
     * Gated by `Workflow.getVersion`, which records a marker for new executions and returns
     * DEFAULT_VERSION when replaying a history without one, so executions started before the
     * branch existed keep the callback wait.
     *
     * The version is consulted only after the threshold test, so no marker is written on the
     * callback path. That is safe because the test reads `request.amount`, which is recorded in
     * WorkflowExecutionStarted and therefore identical on every replay. It also means the
     * threshold constant can change without affecting executions already in flight.
     *
     * `TemporalChangeVersion` is upserted by hand: the Java SDK does not record it. It makes
     * the remaining patch steps queryable:
     *
     *     temporal workflow list --query 'TemporalChangeVersion IS NULL AND ExecutionStatus="Running"'
     *
     * When that returns nothing, `minSupported` can move to 1 and the old branch can go; when
     * no marker is left, the `getVersion` call can go.
     */
    private fun settlesInline(request: ProcessPayoutRequest): Boolean {
        if (!SettlementThresholds.settlesSynchronously(request.amount.amountMinor)) return false
        val version = Workflow.getVersion(INLINE_SETTLEMENT_CHANGE, Workflow.DEFAULT_VERSION, INLINE_SETTLEMENT_VERSION)
        if (version == Workflow.DEFAULT_VERSION) return false
        Workflow.upsertTypedSearchAttributes(
            TEMPORAL_CHANGE_VERSION.valueSet(listOf("$INLINE_SETTLEMENT_CHANGE-$version")),
        )
        return true
    }

    /**
     * Settles in one activity call: no timer and no signal. SETTLING_WITH_BANK records that
     * this is the path taken.
     */
    private fun settleWithBankInline(request: ProcessPayoutRequest): BankStatus {
        advance(BusinessStatus.SETTLING_WITH_BANK, "Low-value payout - the bank answers inline")
        return bankSettlement.settleWithBank(
            BankSettlementRequest(
                payoutId = request.payoutId,
                bankReference = bankReference.orEmpty(),
                idempotencyKey = request.idempotencyKey,
            ),
        ).status
    }

    private fun awaitBankStatus(): BankStatus {
        val summary = "Waiting for bank payment status"
        advance(BusinessStatus.AWAITING_BANK_CONFIRMATION, summary)
        // Ordered by callback like the approval wait, and the timer is owned the same way.
        val deadline = Workflow.newCancellationScope(Runnable {
            Workflow.newTimer(
                Duration.ofSeconds(bankCallbackTimeoutSeconds()),
                TimerOptions.newBuilder().setSummary(summary).build(),
            )
                .thenApply { bankDeadlinePassed = true }
        })
        deadline.run()
        Workflow.await { bankStatus != null || bankDeadlinePassed }
        deadline.cancel("bank status received")
        return bankStatus ?: BankStatus.UNKNOWN
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
        ledgerOutcome.markPayout(MarkPayoutRequest(request.payoutId, "COMPLETED"))
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
                // Matched on the failure type, not the message: a refused inline settlement
                // throws ApplicationFailure("BankRejected"), whose message the substring check
                // below does not match.
                failureTypeOf(e) == "BankRejected" -> FailureCategory.BANK_REJECTED
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
            ledgerOutcome.markPayout(
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

    /** The ApplicationFailure type an activity failed with, or null if it was not one. */
    private fun failureTypeOf(e: Exception): String? =
        ((e as? ActivityFailure)?.cause as? ApplicationFailure)?.type

    private fun advance(next: BusinessStatus, detail: String) {
        status = next
        step = detail
        history += "${next.name}: $detail"
        Workflow.setCurrentDetails("**${next.name}** - $detail")
        // Every transition is recorded in `history` for the Query and in setCurrentDetails
        // for the timeline. Only the visibility write is restricted to [FINDABLE_STATUSES];
        // each one costs a command, a history event and a visibility task.
        if (!visibilityMilestonesOnly || next in FINDABLE_STATUSES) {
            Workflow.upsertTypedSearchAttributes(BUSINESS_STATUS.valueSet(next.name))
        }
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
     * The bank accepted the instruction, never confirmed it, and polling got no answer.
     *
     * The payout compensates on this outcome and is flagged `UNKNOWN_BANK_STATUS`. If the
     * instruction did settle at the bank, releasing the reservation pays out twice.
     */
    private class BankStatusUnresolved :
        RuntimeException("bank never confirmed and polling was exhausted")

    private companion object {
        /**
         * The patch id and its version. The marker in every recorded history is keyed on this
         * string, so renaming it makes those histories read as unpatched.
         */
        const val INLINE_SETTLEMENT_CHANGE = "inline-settlement-for-low-value"
        const val INLINE_SETTLEMENT_VERSION = 1

        const val VISIBILITY_MILESTONES_CHANGE = "visibility-milestones-only"
        const val VISIBILITY_MILESTONES_VERSION = 1

        /**
         * The statuses written to the visibility store, so a payout can be found in them by a
         * visibility query. Other transitions are recorded only in the Query's history list
         * and in the timeline.
         *
         * The set has to cover:
         *  - the states `awaitStatus` blocks on in both test suites and in contract-test.sh
         *    (AWAITING_APPROVAL, AWAITING_BANK_CONFIRMATION, COMPLETED, FAILED, CANCELLED),
         *    since BusinessStatusListener is driven by this upsert;
         *  - everything in StatusCollector.TRACKED, which the business-outcomes panel reads;
         *  - POLLING_BANK_STATUS and COMPENSATING, the states a payout occupies for a while
         *    without being parked on a signal.
         *
         * setOf, not hashSetOf: documented iteration order.
         */
        val FINDABLE_STATUSES = setOf(
            BusinessStatus.AWAITING_APPROVAL,
            BusinessStatus.AWAITING_BANK_CONFIRMATION,
            BusinessStatus.POLLING_BANK_STATUS,
            BusinessStatus.COMPENSATING,
            BusinessStatus.COMPENSATED,
            BusinessStatus.COMPLETED,
            BusinessStatus.FAILED,
            BusinessStatus.CANCELLED,
            BusinessStatus.UNKNOWN_BANK_STATUS,
        )

        /** Server-defined; listed by `temporal operator search-attribute list`. */
        val TEMPORAL_CHANGE_VERSION = SearchAttributeKey.forKeywordList("TemporalChangeVersion")

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
