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
     * Throws a retryable failure while the bank still says "pending", so the retry policy
     * on the stub does the polling and every attempt lands in the event history.
     */
    @ActivityMethod
    fun pollBankStatus(request: BankStatusProbeRequest): BankStatusProbeResponse
}

@ActivityInterface
interface NotificationActivities {
    @ActivityMethod
    fun notify(request: NotifyRequest): NotifyResponse
}
