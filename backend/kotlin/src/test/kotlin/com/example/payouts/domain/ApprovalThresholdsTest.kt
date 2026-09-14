package com.example.payouts.domain

import com.example.payouts.model.domain.ApprovalThresholds
import com.example.payouts.model.domain.ApprovalTier
import com.example.payouts.model.domain.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/**
 * The thresholds decide whether a payout blocks on a human, so the boundaries are worth
 * pinning exactly. Note the asymmetry in `tierFor`: the L1 band is closed at the top
 * (`<= SENIOR_FROM_MINOR`), so exactly $1,000.00 is L1 and SENIOR starts one cent above it.
 */
class ApprovalThresholdsTest {

    @ParameterizedTest(name = "{0} minor USD -> {1}")
    @CsvSource(
        "0,            NONE",
        "49999,        NONE",   // $499.99 -- last cent below the L1 threshold
        "50000,        L1",     // $500.00 -- L1 starts here
        "99999,        L1",
        "100000,       L1",     // $1,000.00 is still L1: the comparison is <=, not <
        "100001,       SENIOR", // one cent more and it needs senior sign-off
        "5000000,      SENIOR",
    )
    fun `tierFor pins both band boundaries`(usdEquivalentMinor: Long, expected: ApprovalTier) {
        assertEquals(expected, ApprovalThresholds.tierFor(usdEquivalentMinor))
    }

    @ParameterizedTest(name = "{0} minor USD -> band {1}")
    @CsvSource(
        "25000,   low",
        "50000,   approval",
        "100000,  approval",
        "250000,  high-value",
    )
    fun `bandFor labels each tier for the UI`(usdEquivalentMinor: Long, expected: String) {
        assertEquals(expected, ApprovalThresholds.bandFor(usdEquivalentMinor))
    }

    @Test
    fun `thresholds are the documented USD minor-unit values`() {
        assertEquals(50_000L, ApprovalThresholds.L1_FROM_MINOR, "\$500.00")
        assertEquals(100_000L, ApprovalThresholds.SENIOR_FROM_MINOR, "\$1,000.00")
    }

    @Test
    fun `money renders minor units as a grouped decimal amount`() {
        assertEquals("250.00 USD", Money(25_000, "USD").toString())
        assertEquals("2,500.00 SGD", Money(250_000, "SGD").toString())
        assertEquals("0.01 USD", Money(1, "USD").toString())
    }
}
