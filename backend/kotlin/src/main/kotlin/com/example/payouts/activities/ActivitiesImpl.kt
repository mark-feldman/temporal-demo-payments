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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every activity is: pause, log a line, return a canned result. No transport simulation,
 * no fake HTTP or SFTP clients -- the demo is about retries, timers, signals and
 * compensation, not about how convincingly a mock can pretend to be a bank.
 *
 * None of this touches determinism. Activities are not re-executed on replay (the recorded
 * result is read from Event History), and activities are exactly where Temporal wants
 * non-deterministic work to live. Blocking, real timers and coroutines are all fine here.
 */
@Component
class ActivityMock(private val scenarios: ScenarioStore) {
    private val log = LoggerFactory.getLogger("payouts.activity")

    /**
     * runBlocking sits at the activity boundary and nowhere else. The signature has to stay
     * non-suspend because the Temporal Java SDK invokes activities reflectively and cannot
     * accept a Continuation parameter.
     */
    fun <T> run(step: String, payoutId: String, pause: Duration, result: () -> T): T = runBlocking {
        val attempt = Activity.getExecutionContext().info.attempt
        scenarios.maybeFail(step, payoutId, attempt)
        delay(pause)
        log.info("[{}] {} (attempt {})", payoutId, step, attempt)
        result()
    }

    fun currentAttempt(): Int = Activity.getExecutionContext().info.attempt
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class ValidationActivitiesImpl(private val mock: ActivityMock) : ValidationActivities {
    override fun validatePayout(request: ValidatePayoutRequest) =
        mock.run("validatePayout", request.payoutId, 200.milliseconds) {
            ValidatePayoutResponse(valid = true, detail = "beneficiary, wallet and rail constraints OK")
        }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class LedgerActivitiesImpl(private val mock: ActivityMock) : LedgerActivities {
    override fun reserveFunds(request: ReserveFundsRequest) =
        mock.run("reserveFunds", request.payoutId, 300.milliseconds) {
            ReserveFundsResponse(reservationId = "RES-${request.payoutId.takeLast(8)}")
        }

    override fun releaseReservedFunds(request: ReleaseFundsRequest) =
        mock.run("releaseReservedFunds", request.payoutId, 250.milliseconds) {
            ReleaseFundsResponse(released = true)
        }

    override fun markPayout(request: MarkPayoutRequest) =
        mock.run("markPayout", request.payoutId, 200.milliseconds) {
            MarkPayoutResponse(recorded = true)
        }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class FxActivitiesImpl(private val mock: ActivityMock) : FxActivities {
    override fun validateFxQuote(request: ValidateFxQuoteRequest) =
        mock.run("validateFxQuote", request.payoutId, 200.milliseconds) {
            val rate = FX_RATES[request.amount.currency] ?: 1.0
            ValidateFxQuoteResponse(
                rate = rate,
                usdEquivalentMinor = (request.amount.amountMinor * rate).toLong(),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
        }

    private companion object {
        /** Canned. A demo rate table, not a market feed. */
        val FX_RATES = mapOf("USD" to 1.0, "SGD" to 0.74, "AUD" to 0.66, "CNY" to 0.14, "EUR" to 1.08)
    }
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class RailActivitiesImpl(private val mock: ActivityMock) : RailActivities {
    // `rail` is a value, not a behaviour: it differs in the log line and nothing else.
    override fun submitToRail(request: SubmitToRailRequest) =
        mock.run("submitToRail", request.payoutId, 600.milliseconds) {
            SubmitToRailResponse(
                accepted = true,
                bankReference = "BANK-${request.rail}-${request.payoutId.substringAfter("po-")}",
                attempt = mock.currentAttempt(),
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
     * The bank reports "pending" for the first few attempts, then gives a real answer.
     * Pending is thrown as a RETRYABLE failure, so the stub's retry policy is what does the
     * polling -- no sleep loop in workflow code, and every attempt is an event you can point at.
     */
    override fun pollBankStatus(request: BankStatusProbeRequest) =
        mock.run("pollBankStatus", request.payoutId, 400.milliseconds) {
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
}

@Component
@ActivityImpl(taskQueues = ["payouts"])
class NotificationActivitiesImpl(private val mock: ActivityMock) : NotificationActivities {
    override fun notify(request: NotifyRequest) =
        mock.run("notify", request.payoutId, 150.milliseconds) { NotifyResponse(sent = true) }
}
