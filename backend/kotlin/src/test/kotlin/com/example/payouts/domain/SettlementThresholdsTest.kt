package com.example.payouts.domain

import com.example.payouts.model.domain.ApprovalThresholds
import com.example.payouts.model.domain.SettlementThresholds
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The boundary decides whether a payout waits on a signal. Note the asymmetry against
 * [ApprovalThresholds]: `settlesSynchronously` is a strict `<`, so the threshold value itself
 * takes the callback path.
 */
class SettlementThresholdsTest {

    @ParameterizedTest(name = "{0} minor -> settles inline: {1}")
    @CsvSource(
        "1,       true",
        "9999,    true",   // last cent on the inline path
        "10000,   false",  // the callback path starts here
        "25000,   false",  // what the manual demo scenarios start at
        "250000,  false",
    )
    fun `the inline-settlement boundary is exact`(amountMinor: Long, inline: Boolean) {
        assertEquals(inline, SettlementThresholds.settlesSynchronously(amountMinor))
    }

    @Test
    fun `the threshold is the documented USD minor-unit value`() {
        assertEquals(10_000L, SettlementThresholds.SYNC_BELOW_MINOR, "\$100.00")
    }

    @Test
    fun `the settlement band sits below the approval band, so the two nest`() {
        // If the sync threshold rose above the L1 threshold the bands would cross, and a
        // payout could require approval while also being settled inline.
        assertTrue(
            SettlementThresholds.SYNC_BELOW_MINOR < ApprovalThresholds.L1_FROM_MINOR,
            "inline settlement must stop before approval starts",
        )
        // The band between them has to be non-empty: it is where a payout waits for a
        // callback without requiring approval, which the unknown-status scenario uses.
        assertFalse(
            SettlementThresholds.settlesSynchronously(ApprovalThresholds.L1_FROM_MINOR - 1),
            "the callback-without-approval band must exist",
        )
    }
}
