package com.example.payouts.app

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * These are plain value assertions, and they earn their place for one reason: the
 * COMPENSATION profile depends on a Temporal API that reads backwards.
 *
 * `setMaximumAttempts(0)` does NOT mean "unlimited" -- it means "use the default". Unlimited
 * is what you get by leaving it UNSET, which also surfaces as 0 on the getter. So the
 * assertion below is not "attempts are zero", it is "attempts were never set", and the day
 * someone writes `setMaximumAttempts(0)` thinking it means forever, nothing else would notice.
 */
class RetryProfilesTest {

    @Test
    fun `compensation never gives up and backs off flat`() {
        val compensation = RetryProfiles.COMPENSATION
        assertEquals(
            0,
            compensation.maximumAttempts,
            "maximumAttempts must stay UNSET; a compensation that stops leaves funds reserved",
        )
        assertEquals(
            1.0,
            compensation.backoffCoefficient,
            "flat retries -- 1.0 is the SDK minimum and is what keeps the interval constant",
        )
        assertEquals(Duration.ofSeconds(5), compensation.initialInterval)
    }

    @Test
    fun `the fast profile is tuned to be legible in a short local run`() {
        val fast = RetryProfiles.FAST
        assertEquals(Duration.ofSeconds(1), fast.initialInterval)
        assertEquals(1.1, fast.backoffCoefficient)
        assertEquals(Duration.ofSeconds(20), fast.maximumInterval)
        assertEquals(8, fast.maximumAttempts)
    }

    @Test
    fun `the realistic profile backs off the way production would`() {
        val realistic = RetryProfiles.REALISTIC
        assertEquals(Duration.ofSeconds(2), realistic.initialInterval)
        assertEquals(2.0, realistic.backoffCoefficient)
        assertEquals(Duration.ofSeconds(60), realistic.maximumInterval)
        assertEquals(8, realistic.maximumAttempts)
    }

    @Test
    fun `byName is case-insensitive and falls back to FAST`() {
        assertSame(RetryProfiles.REALISTIC, RetryProfiles.byName("realistic"))
        assertSame(RetryProfiles.REALISTIC, RetryProfiles.byName("REALISTIC"))
        assertSame(RetryProfiles.FAST, RetryProfiles.byName("fast"))
        assertSame(RetryProfiles.FAST, RetryProfiles.byName("nonsense"))
        assertSame(RetryProfiles.FAST, RetryProfiles.byName(""))
    }
}
