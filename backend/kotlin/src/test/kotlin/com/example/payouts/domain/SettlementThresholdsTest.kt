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
 * The boundary decides whether a payout waits on a signal at all, so it is worth pinning
 * exactly. Note the asymmetry against [ApprovalThresholds]: `settlesSynchronously` is a strict
 * `<`, so the threshold value itself takes the callback path.
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
        // Under \$100 inline, \$100-\$500 callback, over \$500 callback plus a human. If the
        // sync threshold ever rose above the L1 threshold the bands would cross, and a payout
        // could need a human while also being settled inline -- approval would be gating a
        // wait that no longer exists.
        assertTrue(
            SettlementThresholds.SYNC_BELOW_MINOR < ApprovalThresholds.L1_FROM_MINOR,
            "inline settlement must stop before approval starts",
        )
        // The band between them also has to be non-empty, or nothing exercises "waits for a
        // callback but needs no human" -- which is the band the simulator's unknown-status
        // scenario has to sit in.
        assertFalse(
            SettlementThresholds.settlesSynchronously(ApprovalThresholds.L1_FROM_MINOR - 1),
            "the callback-without-approval band must exist",
        )
    }
}
