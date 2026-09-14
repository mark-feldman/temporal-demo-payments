package com.example.payouts.api

import io.temporal.api.workflowservice.v1.CountWorkflowExecutionsRequest
import io.temporal.serviceclient.WorkflowServiceStubs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Business metrics come from the API layer asking Temporal, never from workflow code --
 * a counter incremented inside a workflow double-counts on every replay.
 *
 * It also shows what the custom search attributes are for: the counts come from a
 * visibility query over `businessStatus`, the same query you would type into the
 * Temporal Web filter box.
 */
@Component
@ConditionalOnProperty(name = ["demo.role"], havingValue = "primary", matchIfMissing = true)
class StatusCollector(
    private val service: WorkflowServiceStubs,
    private val metrics: BusinessMetrics,
    @Value("\${spring.temporal.namespace:default}") private val namespace: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 5_000, fixedRate = 5_000)
    fun collect() {
        TRACKED.forEach { status ->
            runCatching { count("businessStatus = '$status'") }
                .onSuccess { metrics.setCurrentStatus(status, it) }
                .onFailure { log.debug("count for {} failed: {}", status, it.message) }
        }
    }

    private fun count(query: String): Int =
        service.blockingStub().countWorkflowExecutions(
            CountWorkflowExecutionsRequest.newBuilder()
                .setNamespace(namespace)
                .setQuery(query)
                .build(),
        ).count.toInt()

    private companion object {
        val TRACKED = listOf(
            "COMPLETED", "FAILED", "CANCELLED", "COMPENSATED",
            "AWAITING_APPROVAL", "AWAITING_BANK_CONFIRMATION", "UNKNOWN_BANK_STATUS",
        )
    }
}
