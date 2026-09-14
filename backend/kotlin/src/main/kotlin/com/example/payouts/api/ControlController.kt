package com.example.payouts.api

import com.example.payouts.scenario.ScenarioStore
import io.temporal.serviceclient.WorkflowServiceStubs
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/demo-api")
class ControlController(
    private val scenarios: ScenarioStore,
    private val service: WorkflowServiceStubs,
    @Value("\${spring.temporal.namespace:default}") private val namespace: String,
) {

    /** The backend status badge is the only consumer -- and it goes red on kill -9. */
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val reachable = runCatching {
            service.blockingStub().getSystemInfo(
                io.temporal.api.workflowservice.v1.GetSystemInfoRequest.newBuilder().build(),
            )
        }.isSuccess
        return mapOf(
            "sdkLanguage" to "Kotlin",
            "sdk" to "Temporal Java SDK 1.38.0",
            "taskQueue" to "payouts",
            "namespace" to namespace,
            "worker" to if (reachable) "UP" else "DOWN",
            "serverReachable" to reachable,
        )
    }

    @GetMapping("/scenarios")
    fun scenarios() = scenarios.all()

    /**
     * Clears scenario config and counters only. It does NOT terminate workflows or empty
     * the workflow list -- that needs a fresh --db-filename, which is reset-demo.sh's job.
     */
    @PostMapping("/reset")
    fun reset(): Map<String, Any> {
        scenarios.clear()
        return mapOf(
            "cleared" to "scenario config",
            "note" to "Workflow history is untouched. Use scripts/reset-demo.sh for a clean slate.",
        )
    }
}
