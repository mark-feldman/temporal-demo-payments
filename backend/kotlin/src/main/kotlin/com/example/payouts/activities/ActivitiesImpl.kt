package com.example.payouts.activities

import com.example.payouts.model.activity.*
import com.example.payouts.model.domain.BankStatus
import io.temporal.failure.ApplicationFailure
import com.example.payouts.scenario.ScenarioStore
import io.temporal.activity.Activity
import io.temporal.spring.boot.ActivityImpl
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every activity pauses, logs a line and returns a fixed result.
 *
 * Activities are not re-executed on replay -- the recorded result is read from Event History --
 * so blocking, real timers and coroutines are all safe here.
 */
@Component
class ActivityMock(private val scenarios: ScenarioStore) {
    private val log = LoggerFactory.getLogger("payouts.activity")

    /**
     * runBlocking sits at the activity boundary and nowhere else. The signature is non-suspend:
     * the Temporal Java SDK invokes activities reflectively and cannot pass a Continuation.
     */
    fun <T> run(step: String, payoutId: String, result: () -> T): T = runBlocking {
        val attempt = Activity.getExecutionContext().info.attempt
        scenarios.maybeFail(step, payoutId, attempt)
        // Randomised. Ordinary Random is safe in an activity: activities are not re-executed
        // on replay.
        delay(Random.nextLong(1_000, 2_500).milliseconds)
        log.info("[{}] {} (attempt {})", payoutId, step, attempt)
        result()
    }

    fun currentAttempt(): Int = Activity.getExecutionContext().info.attempt
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class ValidationActivitiesImpl(private val mock: ActivityMock) : ValidationActivities {
    override fun validatePayout(request: ValidatePayoutRequest) =
        mock.run("validatePayout", request.payoutId) {
            ValidatePayoutResponse(valid = true, detail = "beneficiary, wallet and rail constraints OK")
        }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class LedgerActivitiesImpl(private val mock: ActivityMock) : LedgerActivities {
    override fun reserveFunds(request: ReserveFundsRequest) =
        mock.run("reserveFunds", request.payoutId) {
            ReserveFundsResponse(reservationId = "RES-${request.payoutId.takeLast(8)}")
        }

    override fun releaseReservedFunds(request: ReleaseFundsRequest) =
        mock.run("releaseReservedFunds", request.payoutId) {
            ReleaseFundsResponse(released = true)
        }

    override fun markPayout(request: MarkPayoutRequest) =
        mock.run("markPayout", request.payoutId) {
            MarkPayoutResponse(recorded = true)
        }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class FxActivitiesImpl(private val mock: ActivityMock) : FxActivities {
    override fun validateFxQuote(request: ValidateFxQuoteRequest) =
        mock.run("validateFxQuote", request.payoutId) {
            val rate = FX_RATES[request.amount.currency] ?: 1.0
            ValidateFxQuoteResponse(
                rate = rate,
                usdEquivalentMinor = (request.amount.amountMinor * rate).toLong(),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
        }

    private companion object {
        /** A fixed rate table. */
        val FX_RATES = mapOf("USD" to 1.0, "SGD" to 0.74, "AUD" to 0.66, "CNY" to 0.14, "EUR" to 1.08)
    }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class RailActivitiesImpl(private val mock: ActivityMock) : RailActivities {
    // `rail` affects the log line and the bank reference prefix, and nothing else.
    override fun submitToRail(request: SubmitToRailRequest) =
        mock.run("submitToRail", request.payoutId) {
            SubmitToRailResponse(
                accepted = true,
                bankReference = "BANK-${request.rail}-${request.payoutId.substringAfter("po-")}",
                attempt = mock.currentAttempt(),
            )
        }

    override fun reverseRailInstruction(request: ReverseRailRequest) =
        mock.run("reverseRailInstruction", request.payoutId) {
            ReverseRailResponse(
                reversed = true,
                reversalReference = "REV-${request.rail}-${request.payoutId.substringAfter("po-")}",
            )
        }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class BankActivitiesImpl(
    private val mock: ActivityMock,
    private val scenarios: ScenarioStore,
) : BankActivities {
    /**
     * Reports "pending" for the first `pollsBeforeResolution` attempts, then gives a real
     * answer. Pending is a retryable failure, so the stub's retry policy performs the polling.
     * Intermediate attempts are not written to Event History; only the final attempt number is.
     */
    override fun pollBankStatus(request: BankStatusProbeRequest) =
        mock.run("pollBankStatus", request.payoutId) {
            val config = scenarios.get(request.payoutId)
            val attempt = mock.currentAttempt()
            if (config.pollingNeverResolves || attempt <= config.pollsBeforeResolution) {
                throw ApplicationFailure.newFailure(
                    "bank still reports the instruction as pending (poll $attempt)",
                    "BankStatusPending",
                )
            }
            BankStatusProbeResponse(status = BankStatus.valueOf(config.resolvedStatus))
        }

    /**
     * The inline answer for a low-value instruction: [SYNC_SETTLEMENT_SUCCESS_PCT] settle, the
     * rest are refused.
     *
     * `mock.run` applies the same failure injection as every other step, so a scenario staged
     * with `behavior=REJECTED, step=settleWithBank` throws a non-retryable BankRejected
     * instead of rolling for an outcome.
     */
    override fun settleWithBank(request: BankSettlementRequest) =
        mock.run("settleWithBank", request.payoutId) {
            val settled = Random.nextInt(100) < SYNC_SETTLEMENT_SUCCESS_PCT
            BankSettlementResponse(status = if (settled) BankStatus.COMPLETED else BankStatus.REJECTED)
        }

    private companion object {
        /** Share of inline settlements that succeed. Rolled in the activity, so it replays. */
        const val SYNC_SETTLEMENT_SUCCESS_PCT = 80
    }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class NotificationActivitiesImpl(private val mock: ActivityMock) : NotificationActivities {
    override fun notify(request: NotifyRequest) =
        mock.run("notify", request.payoutId) { NotifyResponse(sent = true) }
}
