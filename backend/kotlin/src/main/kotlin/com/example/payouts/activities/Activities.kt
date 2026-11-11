package com.example.payouts.activities

import com.example.payouts.model.activity.*
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

@ActivityInterface
interface ValidationActivities {
    @ActivityMethod
    fun validatePayout(request: ValidatePayoutRequest): ValidatePayoutResponse
}

@ActivityInterface
interface LedgerActivities {
    @ActivityMethod
    fun reserveFunds(request: ReserveFundsRequest): ReserveFundsResponse

    @ActivityMethod
    fun releaseReservedFunds(request: ReleaseFundsRequest): ReleaseFundsResponse

    @ActivityMethod
    fun markPayout(request: MarkPayoutRequest): MarkPayoutResponse
}

@ActivityInterface
interface FxActivities {
    @ActivityMethod
    fun validateFxQuote(request: ValidateFxQuoteRequest): ValidateFxQuoteResponse
}

@ActivityInterface
interface RailActivities {
    @ActivityMethod
    fun submitToRail(request: SubmitToRailRequest): SubmitToRailResponse

    /** Reverses an instruction the bank already accepted. Part of the saga unwind. */
    @ActivityMethod
    fun reverseRailInstruction(request: ReverseRailRequest): ReverseRailResponse
}

@ActivityInterface
interface BankActivities {
    /**
     * Asks the bank what happened to an instruction it accepted but never confirmed.
     * Throws a retryable failure while the bank still reports "pending", so the retry policy
     * on the stub performs the polling.
     */
    @ActivityMethod
    fun pollBankStatus(request: BankStatusProbeRequest): BankStatusProbeResponse

    /**
     * Settles a low-value instruction in one call: the rail answers COMPLETED or REJECTED
     * inline, so the workflow never waits on a callback.
     *
     * The outcome is decided here rather than in workflow code. Activity results are recorded
     * in Event History and activities are not re-executed on replay, so a random choice inside
     * an activity replays as whatever it returned.
     */
    @ActivityMethod
    fun settleWithBank(request: BankSettlementRequest): BankSettlementResponse
}

@ActivityInterface
interface NotificationActivities {
    @ActivityMethod
    fun notify(request: NotifyRequest): NotifyResponse
}

/**
 * The seven stages of the streamed payment (scenario 6), plus the two reversals its failure
 * path runs. Each publishes progress to the stream hosted by its parent workflow.
 */
@ActivityInterface
interface PaymentStageActivities {
    @ActivityMethod
    fun validatePayment(request: StageRequest): StageResponse

    @ActivityMethod
    fun approvePayment(request: StageRequest): StageResponse

    @ActivityMethod
    fun transferFunds(request: StageRequest): StageResponse

    @ActivityMethod
    fun postToLedger(request: StageRequest): StageResponse

    @ActivityMethod
    fun verifyPayment(request: StageRequest): StageResponse

    @ActivityMethod
    fun confirmPayment(request: StageRequest): StageResponse

    @ActivityMethod
    fun settlePayment(request: StageRequest): StageResponse

    @ActivityMethod
    fun reverseTransfer(request: StageRequest): StageResponse

    @ActivityMethod
    fun reverseLedgerEntry(request: StageRequest): StageResponse
}

/**
 * Subscribes to the processing workflow's `status` topic and signals each item to the workflow
 * that scheduled this activity. Long-running: lives as long as the processing workflow does.
 */
@ActivityInterface
interface StreamRelayActivities {
    @ActivityMethod
    fun relayStageUpdates(request: RelayRequest): RelayResponse
}
