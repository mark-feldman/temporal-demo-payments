package com.example.payouts.workflow

import com.example.payouts.activities.BankActivities
import com.example.payouts.activities.FxActivities
import com.example.payouts.activities.LedgerActivities
import com.example.payouts.activities.NotificationActivities
import com.example.payouts.activities.RailActivities
import com.example.payouts.activities.ValidationActivities
import com.example.payouts.model.activity.*
import com.example.payouts.model.domain.BankStatus
import com.example.payouts.model.domain.BusinessStatus
import com.example.payouts.model.domain.Money
import com.example.payouts.model.domain.Rail
import com.example.payouts.model.domain.Region
import com.example.payouts.model.workflow.BankStatusUpdateRequest
import com.example.payouts.support.PayoutWorkflowTestBase
import com.example.payouts.support.payoutRequest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

/**
 * The activity-mocking pattern the SDK documents, kept as one worked example alongside the
 * hand-written fake the other tests use.
 *
 * Two Kotlin-specific traps are on display:
 *
 *  - `withSettings().withoutAnnotations()` is mandatory. Without it Mockito copies
 *    `@ActivityInterface` onto the generated mock class, and the SDK's registration then sees
 *    an annotated class rather than an annotated interface and rejects it.
 *  - Every method that the workflow calls must be stubbed. Mockito returns null by default,
 *    and these interfaces all declare non-null Kotlin return types, so an unstubbed method is
 *    fatal rather than merely empty -- which is precisely why the rest of the suite prefers a
 *    real fake.
 */
class PayoutWorkflowMockitoTest : PayoutWorkflowTestBase() {

    private val validation = mock(ValidationActivities::class.java, withSettings().withoutAnnotations())
    private val ledger = mock(LedgerActivities::class.java, withSettings().withoutAnnotations())
    private val fx = mock(FxActivities::class.java, withSettings().withoutAnnotations())
    private val rail = mock(RailActivities::class.java, withSettings().withoutAnnotations())
    private val bank = mock(BankActivities::class.java, withSettings().withoutAnnotations())
    private val notifier = mock(NotificationActivities::class.java, withSettings().withoutAnnotations())

    private fun stubHappyPath() {
        whenever(validation.validatePayout(any())).thenReturn(ValidatePayoutResponse(true, "ok"))
        whenever(ledger.reserveFunds(any())).thenReturn(ReserveFundsResponse("RES-1"))
        whenever(ledger.releaseReservedFunds(any())).thenReturn(ReleaseFundsResponse(true))
        whenever(ledger.markPayout(any())).thenReturn(MarkPayoutResponse(true))
        whenever(fx.validateFxQuote(any())).thenReturn(ValidateFxQuoteResponse(1.0, 25_000, 0))
        whenever(rail.submitToRail(any())).thenReturn(SubmitToRailResponse(true, "BANK-MOCK-1", 1))
        whenever(rail.reverseRailInstruction(any())).thenReturn(ReverseRailResponse(true, "REV-MOCK-1"))
        whenever(bank.pollBankStatus(any())).thenReturn(BankStatusProbeResponse(BankStatus.COMPLETED))
        whenever(notifier.notify(any())).thenReturn(NotifyResponse(true))
    }

    @Test
    fun `the workflow hands each activity exactly the request the contract specifies`() {
        stubHappyPath()
        startWorkerWith(validation, ledger, fx, rail, bank, notifier)

        val stub = newStub("mockito-request-shapes")
        start(
            stub,
            payoutRequest(
                payoutId = "po-000777",
                amountMinor = 31_000,
                currency = "SGD",
                customerId = "cust-0099",
                rail = Rail.SFTP,
                region = Region.AU,
            ),
        )
        awaitStatus(stub, BusinessStatus.AWAITING_BANK_CONFIRMATION)
        stub.bankStatusUpdate(BankStatusUpdateRequest(BankStatus.COMPLETED))
        resultOf(stub)

        // Validation gets the payout's identity and routing, but not the idempotency key.
        verify(validation).validatePayout(
            ValidatePayoutRequest(
                payoutId = "po-000777",
                customerId = "cust-0099",
                amount = Money(31_000, "SGD"),
                rail = Rail.SFTP,
                region = Region.AU,
            ),
        )

        verify(ledger).reserveFunds(ReserveFundsRequest("po-000777", Money(31_000, "SGD")))
        verify(fx).validateFxQuote(ValidateFxQuoteRequest("po-000777", Money(31_000, "SGD")))

        // The rail submission is the only activity that carries the idempotency key.
        verify(rail).submitToRail(
            SubmitToRailRequest(
                payoutId = "po-000777",
                rail = Rail.SFTP,
                region = Region.AU,
                amount = Money(31_000, "SGD"),
                idempotencyKey = "po-000777-rail-1",
            ),
        )

        verify(ledger).markPayout(MarkPayoutRequest("po-000777", "COMPLETED"))

        val notifications = argumentCaptor<NotifyRequest>()
        verify(notifier).notify(notifications.capture())
        assertEquals("customer", notifications.firstValue.channel)
        assertEquals("Payout completed", notifications.firstValue.message)

        // A settled payout never touches the compensations or the bank poll.
        verify(ledger, never()).releaseReservedFunds(any())
        verify(rail, never()).reverseRailInstruction(any())
        verify(bank, never()).pollBankStatus(any())
    }
}
