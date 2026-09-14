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
}

@ActivityInterface
interface NotificationActivities {
    @ActivityMethod
    fun notify(request: NotifyRequest): NotifyResponse
}
