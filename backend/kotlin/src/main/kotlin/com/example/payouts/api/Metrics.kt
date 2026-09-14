package com.example.payouts.api

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Business metrics, emitted from the API layer -- never from workflow code, where a
 * counter would double-count on every replay.
 */
@Component
class BusinessMetrics(private val registry: MeterRegistry) {
    private val gauges = ConcurrentHashMap<String, AtomicInteger>()

    val started: Counter = Counter.builder("payout_workflows_started_total").register(registry)
    val completed: Counter = Counter.builder("payout_workflows_completed_total").register(registry)
    val failed: Counter = Counter.builder("payout_workflows_failed_total").register(registry)
    val compensated: Counter = Counter.builder("payout_workflows_compensated_total").register(registry)
    val approvalTimeouts: Counter = Counter.builder("payout_approval_timeouts_total").register(registry)
    val unknownBankStatus: Counter = Counter.builder("payout_unknown_bank_status_total").register(registry)

    fun activityFailure(activity: String, failureType: String) =
        registry.counter("payout_activity_failures_total", "activity", activity, "failureType", failureType).increment()

    fun railSubmission(rail: String, region: String, outcome: String) =
        registry.counter("payout_rail_submissions_total", "rail", rail, "region", region, "outcome", outcome)
            .increment()

    fun setCurrentStatus(businessStatus: String, value: Int) {
        gauges.computeIfAbsent(businessStatus) {
            registry.gauge("payout_current_status", listOf(io.micrometer.core.instrument.Tag.of("businessStatus", it)), AtomicInteger(0))!!
        }.set(value)
    }
}
