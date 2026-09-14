package com.example.payouts.worker

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class WorkerInfo(val id: Int, val pid: Long, val port: Int, val alive: Boolean)

data class WorkerFleet(
    val running: Int,
    val workers: List<WorkerInfo>,
    /** The cap, reported so the UI can show it rather than letting the button go dead. */
    val max: Int = WorkerSupervisor.MAX_WORKERS,
)

/**
 * Runs the Temporal workers as separate JVMs and supervises them.
 *
 * They are separate processes for two reasons. A WorkerFactory keys its workers by task
 * queue and returns the existing one for a repeated call, so a second worker cannot come
 * from the same JVM. And killing a worker has to leave the API alive -- otherwise the
 * thing that restarts it dies with it.
 *
 * Killing one is a real SIGKILL, not a polling pause: the process goes away, in-flight
 * workflow tasks time out, and when a replacement starts it rebuilds state by replaying
 * history. That is the recovery worth showing.
 */
@Component
@ConditionalOnProperty(name = ["demo.role"], havingValue = "primary", matchIfMissing = true)
class WorkerSupervisor {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workers = ConcurrentHashMap<Int, Process>()

    @EventListener(ApplicationReadyEvent::class)
    fun startInitialWorker() {
        if (workers.isEmpty()) scaleTo(1)
    }

    fun fleet(): WorkerFleet {
        workers.entries.removeIf { !it.value.isAlive }
        val list = workers.entries.sortedBy { it.key }.map { (id, p) ->
            WorkerInfo(id = id, pid = p.pid(), port = portFor(id), alive = p.isAlive)
        }
        return WorkerFleet(running = list.count { it.alive }, workers = list)
    }

    /** SIGKILL, deliberately -- a graceful shutdown would not demonstrate recovery. */
    fun kill(id: Int? = null): WorkerFleet {
        val target = id ?: workers.keys.maxOrNull()
        target?.let { workers.remove(it)?.destroyForcibly()?.also { p -> log.info("killed worker {} pid {}", it, p.pid()) } }
        Thread.sleep(300)
        return fleet()
    }

    fun killAll(): WorkerFleet {
        workers.keys.toList().forEach { kill(it) }
        return fleet()
    }

    /** Clamped to [MAX_WORKERS]: every port above that has no scrape target behind it. */
    fun scaleTo(target: Int): WorkerFleet {
        val capped = target.coerceIn(0, MAX_WORKERS)
        if (capped != target) log.info("worker count {} clamped to the cap of {}", target, MAX_WORKERS)
        while (fleet().running < capped) spawn(nextFreeId())
        while (fleet().running > capped) kill()
        return fleet()
    }

    private fun nextFreeId(): Int = generateSequence(1) { it + 1 }.first { it !in workers.keys }

    private fun spawn(id: Int) {
        val jar = findJar() ?: run { log.error("no bootJar found; run ./gradlew bootJar"); return }
        val port = portFor(id)
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java, "-jar", jar.absolutePath,
            "--spring.profiles.active=worker",
            "--server.port=$port",
        ).apply {
            environment()["WORKER_PORT"] = port.toString()
            redirectOutput(File("/tmp/payout-demo-worker-$id.log"))
            redirectErrorStream(true)
        }.start()
        workers[id] = process
        log.info("started worker {} pid {} on :{}", id, process.pid(), port)
    }

    private fun findJar(): File? =
        sequenceOf(File("build/libs"), File("backend/kotlin/build/libs"))
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.asSequence() ?: emptySequence() }
            .filter { it.name.endsWith(".jar") && !it.name.endsWith("-plain.jar") }
            .firstOrNull()

    @PreDestroy
    fun shutdown() {
        workers.values.forEach { it.destroyForcibly() }
    }

    companion object {
        /**
         * Worker N runs on [BASE_PORT] + N - 1, so the fleet occupies 8091-8100.
         *
         * These two numbers are not free to change on their own. Every port in that range
         * needs a matching scrape target in `config/prometheus/prometheus.yml`, or a worker
         * that starts fine is simply invisible: the fleet count under-reports and every
         * per-instance panel quietly draws a subset. `PrometheusTargetsTest` asserts the
         * range and the config agree.
         *
         * Ten is also about as much as a laptop wants -- each worker is a full Spring Boot JVM
         * alongside the API, the dev server and three containers.
         */
        const val BASE_PORT = 8091
        const val MAX_WORKERS = 10

        fun portFor(id: Int): Int = BASE_PORT + id - 1

        /** Every port the supervisor can ever allocate. */
        fun allPorts(): List<Int> = (1..MAX_WORKERS).map(::portFor)
    }
}
