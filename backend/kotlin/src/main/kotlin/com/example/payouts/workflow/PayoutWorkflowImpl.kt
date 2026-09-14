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
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * The attempt cap on the demo retry policy, drawn per workflow instance so no two executions
 * retry the same number of times.
 *
 * The minimum is a constraint on the demo as much as on the policy. A failure-injection count
 * of [DEMO_MIN_ATTEMPTS] or more exhausts the policy on *some* executions and not others,
 * which turns the retry scenario into a compensation scenario at random. `app.js` picks those
 * counts, so `DemoScenarioDefaultsTest` holds it to this bound.
 */
const val DEMO_MIN_ATTEMPTS = 7
const val DEMO_MAX_ATTEMPTS = 10

/** Registered by @WorkflowImpl; deliberately NOT a Spring bean -- Temporal creates one per execution. */
@WorkflowImpl(taskQueues = [TASK_QUEUE])
class PayoutWorkflowImpl : PayoutWorkflow {

    private val log = Workflow.getLogger(javaClass)

    /**
     * Replay-safe RNG. kotlin.random.Random here would draw a different attempt cap on every
     * replay and break determinism; Workflow.newRandom is seeded per workflow instance and
     * replays identically. Workflow context is available during construction -- the activity
     * stubs below rely on the same thing.
     */
    private val rng: java.util.Random = Workflow.newRandom()

    /**
     * Deliberately slow and persistent so retries are legible rather than instantaneous:
     * [DEMO_MIN_ATTEMPTS]-[DEMO_MAX_ATTEMPTS] attempts, 1s initial, x1.1, capped at 20s.
     */
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
    // Deadline flags flipped by timer callbacks. Callbacks and signal handlers both run in
    // event order, so "did the signal beat the deadline" is answered by which of the two ran
    // first -- not by wall-clock, and not by which promise `await` happened to unblock on.
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

    private val ledger = Workflow.newActivityStub(
        LedgerActivities::class.java,
        ActivityOptions {
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Ledger operation")
            setRetryOptions(demoRetry().toBuilder().setDoNotRetry("InsufficientFunds").build())
        },
    )

    /** Compensation must not give up: attempts unset = unlimited, flat 5s backoff. */
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
            setStartToCloseTimeout(Duration.ofSeconds(10))
            setScheduleToCloseTimeout(Duration.ofMinutes(5))
            setTaskQueue(TASK_QUEUE)
            setSummary("Poll bank for payment status")
            setRetryOptions(demoRetry())
        },
    )

    /**
     * Inline settlement for a low-value instruction. A SEPARATE stub from `bank` because the
     * retry policy means something different here: `bank` retries in order to poll, whereas a
     * settlement that comes back REJECTED is a final answer returned as a value, not an error.
     * `BankRejected` is listed as non-retryable anyway, so a scenario that injects a refusal
     * at this step fails once rather than seven to ten times.
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

    /** Capped at 3 so a dead notifier cannot hold a payout in COMPENSATING forever. */
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
        // Resolved before the first command so the decision is stable for the whole
        // execution, and patched for the same reason as the settlement split: dropping an
        // upsert changes the command stream, and an execution that already recorded one for
        // VALIDATING would not emit it on replay.
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

            // How the bank confirms depends on the size of the payout, and the decision comes
            // from the workflow's own input so it replays identically. Low-value instructions
            // are answered inline by a single activity; larger ones are confirmed out of
            // band, which is the durable-wait-on-a-signal path.
            val reported = if (settlesInline(request)) {
                settleWithBankInline(request)
            } else {
                when (val first = awaitBankStatus()) {
                    // No answer, or an ambiguous one. Ask the bank directly rather than
                    // escalating to a human: the retry policy on the poll stub does the polling.
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
        // Ignore a decision that arrives after the timer has already fired.
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
        advance(BusinessStatus.AWAITING_APPROVAL, "Waiting for $approvalTier approval")
        // An explicit timer rather than await's built-in timeout. If no worker is alive, the
        // signal and the timer both sit in history until one comes back, and await reports a
        // timeout even though the approval genuinely arrived first. Ordering the two through
        // event callbacks answers that correctly and identically on every replay.
        //
        // It has to be owned by a cancellation scope. await(timeout, cond) cancels its own
        // internal timer; a bare newTimer does not, and an uncancelled one is left dangling
        // in history as a TimerStarted with nothing resolving it.
        val deadline = Workflow.newCancellationScope(Runnable {
            Workflow.newTimer(Duration.ofSeconds(approvalTimeoutSeconds()))
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
     * Whether this payout takes the inline-settlement path -- **patched**, not simply switched.
     *
     * Splitting the bank confirmation by amount replaced one branch with another, which is the
     * textbook way to break replay: an execution that already recorded the old branch produces
     * different commands under the new code, and Temporal blocks it rather than failing it
     * loudly. Verified rather than assumed, by replaying a real exported history against the
     * unpatched code:
     *
     *     NonDeterministicException: [TMPRL1100] Failure handling event 37 of type
     *     'EVENT_TYPE_TIMER_STARTED' during replay. Event 37 of type EVENT_TYPE_TIMER_STARTED
     *     does not match command type COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK
     *
     * -- the recorded bank deadline timer, where the new code wanted to schedule settleWithBank.
     * 482 executions were in flight below the threshold at the time.
     *
     * `Workflow.getVersion` records a marker for new executions and returns DEFAULT_VERSION
     * when replaying a history that has none, so pre-change executions keep the wait they
     * already committed to and new ones settle inline.
     *
     * Two things about the shape:
     *
     *  - The version is consulted only AFTER the threshold test, so no marker is written on the
     *    majority path. That is safe because the test reads `request.amount`, which is recorded
     *    in WorkflowExecutionStarted and therefore identical on every replay -- the decision to
     *    consult the marker is itself deterministic. It also makes the threshold constant safe
     *    to change: raise it, and an in-flight payout that was above the old boundary finds no
     *    marker and stays on the path it started.
     *  - The Java SDK does NOT record `TemporalChangeVersion` for you, unlike Python and
     *    TypeScript, so it is upserted by hand. That is what makes the next two steps of the
     *    patch answerable from a query rather than a guess:
     *
     *        temporal workflow list --query 'TemporalChangeVersion IS NULL AND ExecutionStatus="Running"'
     *
     *    When no pre-patch execution is left, `minSupported` moves to 1 and the old branch
     *    goes; when no marker is left, the `getVersion` call itself goes.
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
     * Settle inline. One activity call, one answer, no timer and no signal -- so a low-value
     * payout never spends the 45s bank deadline, and the state it passes through says which
     * path it took rather than leaving that to be inferred from the absence of a wait.
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
        advance(BusinessStatus.AWAITING_BANK_CONFIRMATION, "Waiting for bank payment status")
        // Same ordering argument, and the same need to own the timer.
        val deadline = Workflow.newCancellationScope(Runnable {
            Workflow.newTimer(Duration.ofSeconds(bankCallbackTimeoutSeconds()))
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
                // Matched on the failure TYPE rather than the message. An inline settlement
                // that the bank refuses throws ApplicationFailure("BankRejected"), whose
                // message says "bank" and not "Rail", so the substring check below filed it
                // under VALIDATION -- found by the test for that path, not by reading.
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

    /** The ApplicationFailure type an activity failed with, or null if it was not one. */
    private fun failureTypeOf(e: Exception): String? =
        ((e as? ActivityFailure)?.cause as? ApplicationFailure)?.type

    private fun advance(next: BusinessStatus, detail: String) {
        status = next
        step = detail
        history += "${next.name}: $detail"
        Workflow.setCurrentDetails("**${next.name}** - $detail")
        // Every transition is still recorded -- in `history` for the Query, and in
        // setCurrentDetails for the Temporal timeline. What is rationed is the *visibility*
        // write, because that is the part that costs a command, a history event and a
        // visibility task apiece. Under the high-load preset those upserts were the single
        // biggest per-payout contributor to history-service load: nine of them on a payout
        // that a visibility query can only usefully be asked about twice.
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
     * The bank accepted the instruction and never confirmed, and polling could not get an
     * answer either. Compensating here is a POLICY CHOICE, not a safe default: if the
     * instruction did settle at the bank, releasing the reservation pays out twice. It is
     * defensible only because every automated avenue has been exhausted first -- which is
     * exactly what the polling above is for.
     */
    private class BankStatusUnresolved :
        RuntimeException("bank never confirmed and polling was exhausted")

    private companion object {
        /**
         * The patch id, and the only version of it. NEVER rename this string: the marker in
         * every history already written is keyed on it, and a rename makes those histories
         * look unpatched.
         */
        const val INLINE_SETTLEMENT_CHANGE = "inline-settlement-for-low-value"
        const val INLINE_SETTLEMENT_VERSION = 1

        const val VISIBILITY_MILESTONES_CHANGE = "visibility-milestones-only"
        const val VISIBILITY_MILESTONES_VERSION = 1

        /**
         * The statuses a payout can be *found* sitting in, and therefore the only ones worth
         * writing to the visibility store. Everything else is a transition that lasts
         * milliseconds: real, recorded in the Query's history list and in the timeline, but
         * never the answer to "show me the payouts currently in X".
         *
         * The set is the union of three requirements, so it cannot be trimmed casually:
         *  - the five states `awaitStatus` blocks on in both suites and in contract-test.sh
         *    (AWAITING_APPROVAL, AWAITING_BANK_CONFIRMATION, COMPLETED, FAILED, CANCELLED) --
         *    BusinessStatusListener is driven by this very upsert, so an unpublished state is
         *    one the tests can no longer wait for;
         *  - everything in StatusCollector.TRACKED, which is what the business-outcomes panel
         *    is built from;
         *  - the two states a payout genuinely lingers in without being parked on a signal:
         *    POLLING_BANK_STATUS and COMPENSATING, so both stay filterable in Temporal Web.
         *
         * setOf, not hashSetOf: documented iteration order, and it is only ever read.
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

        /** Server-defined, already registered -- checked with `temporal operator search-attribute list`. */
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
