package com.example.payouts.app

import io.temporal.common.RetryOptions
import io.temporal.common.copy
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Set explicitly rather than relying on defaults, so the policy is readable in code rather
 * than implied. Temporal's default -- 1s initial, x2.0, unlimited attempts -- backs off too
 * far to observe in a short-lived local run, hence the FAST profile.
 */
object RetryProfiles {

    val FAST: RetryOptions = RetryOptions {
        setInitialInterval(500.milliseconds.toJavaDuration())
        setBackoffCoefficient(1.5)
        setMaximumInterval(3.seconds.toJavaDuration())
        setMaximumAttempts(5)
    }

    val REALISTIC: RetryOptions = FAST.copy {
        setInitialInterval(2.seconds.toJavaDuration())
        setBackoffCoefficient(2.0)
        setMaximumInterval(60.seconds.toJavaDuration())
        setMaximumAttempts(8)
    }

    /**
     * Compensation must not give up: a compensation that stops leaves money reserved
     * against a payout that failed. maximumAttempts is deliberately UNSET -- unlimited is
     * the default, and setMaximumAttempts(0) means "use the default", not "unlimited".
     * setBackoffCoefficient(1.0) is valid (the SDK requires >= 1.0) and gives flat retries.
     */
    val COMPENSATION: RetryOptions = RetryOptions {
        setInitialInterval(5.seconds.toJavaDuration())
        setBackoffCoefficient(1.0)
    }

    fun byName(name: String): RetryOptions = when (name.lowercase()) {
        "realistic" -> REALISTIC
        else -> FAST
    }
}
