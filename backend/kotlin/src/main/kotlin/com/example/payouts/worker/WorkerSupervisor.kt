package com.example.payouts.worker

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class WorkerInfo(val id: Int, val pid: Long, val port: Int, val alive: Boolean)

data class WorkerFleet(
    val running: Int,
    val workers: List<WorkerInfo>,
    /** The maximum fleet size the supervisor will grow to. */
    val max: Int = WorkerSupervisor.MAX_WORKERS,
)

/**
 * Runs the Temporal workers as separate JVMs and supervises them.
 *
 * A WorkerFactory keys its workers by task queue and returns the existing one for a repeated
 * call, so one JVM hosts at most one worker for `payouts`. The API process stays alive when a
 * worker is killed, so it can start a replacement.
 *
 * [kill] sends SIGKILL: the process goes away, its in-flight workflow tasks time out, and a
 * replacement rebuilds state by replaying history.
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

    /** SIGKILL the given worker, or the most recently started one. */
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

    /**
     * Grows or shrinks the fleet to [target], clamped to [MAX_WORKERS].
     *
     * The growth loop is bounded at [MAX_WORKERS] attempts and stops on a spawn that did not
     * stay alive, so it always terminates and the caller always gets a response.
     */
    fun scaleTo(target: Int): WorkerFleet {
        val capped = target.coerceIn(0, MAX_WORKERS)
        if (capped != target) log.info("worker count {} clamped to the cap of {}", target, MAX_WORKERS)
        var attempts = 0
        while (fleet().running < capped) {
            if (attempts++ >= MAX_WORKERS) {
                log.error(
                    "gave up growing the fleet to {} after {} attempts -- workers are not staying alive; " +
                        "check /tmp/payout-demo-worker-*.log",
                    capped, attempts,
                )
                break
            }
            if (!spawn(nextFreeId())) break
        }
        while (fleet().running > capped) kill()
        return fleet()
    }

    private fun nextFreeId(): Int = generateSequence(1) { it + 1 }.first { it !in workers.keys }

    /** False if the worker could not be started at all, or started and exited immediately. */
    private fun spawn(id: Int): Boolean {
        val jar = findJar() ?: run {
            log.error("no bootJar found in build/libs; run ./gradlew bootJar")
            return false
        }
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
        // A worker takes seconds to finish booting but its process is alive immediately, so
        // exiting inside this window means it failed to start. Only a live process is put in
        // the map: fleet() prunes dead entries.
        if (process.waitFor(800, TimeUnit.MILLISECONDS)) {
            log.error(
                "worker {} exited immediately (status {}); see /tmp/payout-demo-worker-{}.log",
                id, process.exitValue(), id,
            )
            return false
        }
        workers[id] = process
        log.info("started worker {} pid {} on :{}", id, process.pid(), port)
        return true
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
         * Every port in that range needs a matching scrape target in
         * `config/prometheus/prometheus.yml`, or the worker is not observable.
         * `PrometheusTargetsTest` asserts the range and that config agree.
         */
        const val BASE_PORT = 8091
        const val MAX_WORKERS = 10

        fun portFor(id: Int): Int = BASE_PORT + id - 1

        /** Every port the supervisor can ever allocate. */
        fun allPorts(): List<Int> = (1..MAX_WORKERS).map(::portFor)
    }
}
