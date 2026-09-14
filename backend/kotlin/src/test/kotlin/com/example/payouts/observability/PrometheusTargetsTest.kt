package com.example.payouts.observability

import com.example.payouts.support.repoFile
import com.example.payouts.worker.WorkerSupervisor
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The scrape config has to cover every port the worker fleet can occupy.
 *
 * This is the one seam where a worker starting successfully still leaves no trace: Prometheus
 * only scrapes what it was told about, so an unlisted port means the JVM runs, polls the task
 * queue and does work, while the fleet count under-reports and every per-instance panel draws
 * a subset. Nothing errors. It happened: the list stopped at 8093 while five workers ran, and
 * "Live worker instances" read 4 -- three workers plus the API it was also counting.
 *
 * `WorkerSupervisor` owns the range and this asserts the config agrees with it, so raising
 * MAX_WORKERS without extending the targets fails here rather than in a demo.
 */
class PrometheusTargetsTest {

    private val config = repoFile(
        "../../config/prometheus/prometheus.yml",
        "config/prometheus/prometheus.yml",
    )

    @Suppress("UNCHECKED_CAST")
    private fun targetsByRole(): Map<String, List<Int>> {
        val yaml = Yaml().load<Map<String, Any>>(Files.readString(config))
        val jobs = yaml["scrape_configs"] as? List<Map<String, Any>> ?: fail("no scrape_configs in $config")
        val job = jobs.firstOrNull { it["job_name"] == "payout-demo-worker" }
            ?: fail("no payout-demo-worker job in $config")
        val statics = job["static_configs"] as? List<Map<String, Any>> ?: fail("no static_configs")

        // Grouped by the `role` label, because the API and the workers share one job and only
        // that label tells them apart -- which is also what the fleet-count panel filters on,
        // after it was found counting the API as a worker.
        return statics.groupBy(
            { (it["labels"] as? Map<String, String>)?.get("role") ?: "none" },
            { entry -> (entry["targets"] as List<String>).map { it.substringAfterLast(':').toInt() } },
        ).mapValues { (_, ports) -> ports.flatten().sorted() }
    }

    @Test
    fun `every port the supervisor can allocate has a scrape target`() {
        assertEquals(
            WorkerSupervisor.allPorts().sorted(),
            targetsByRole()["worker"],
            "the worker targets must be exactly ${WorkerSupervisor.BASE_PORT}.." +
                "${WorkerSupervisor.portFor(WorkerSupervisor.MAX_WORKERS)}. A port the fleet " +
                "can reach but Prometheus does not scrape is a worker that runs invisibly; a " +
                "port Prometheus scrapes but the fleet never uses is a target permanently " +
                "down for no reason",
        )
    }

    @Test
    fun `the API is scraped too, and labelled so it is not counted as a worker`() {
        val byRole = targetsByRole()
        assertEquals(listOf(8081), byRole["api"], "the API serves the business counters on 8081")
        assertTrue(
            8081 !in (byRole["worker"] ?: emptyList()),
            "8081 must not carry role=worker: the fleet-count panel filters on that label",
        )
    }
}
