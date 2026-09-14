package com.example.payouts

import com.example.payouts.api.PayoutController
import com.example.payouts.api.SimulationController
import com.example.payouts.api.StatusCollector
import com.example.payouts.api.WorkerController
import com.example.payouts.activities.ValidationActivitiesImpl
import com.example.payouts.simulation.SimulationRunner
import com.example.payouts.worker.WorkerSupervisor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worker processes run the same jar with `demo.role=worker`, which switches off the
 * API-only beans so they do not run once per worker.
 *
 * A controller that injects a primary-only bean must be gated with the same condition, or the
 * worker JVM fails at startup on an unsatisfied dependency. It fails in a forked process whose
 * output goes to /tmp, so the visible symptom is a task queue with nothing polling it. Booting
 * the worker role here turns that into a test failure.
 *
 * No @MockitoBean for WorkerSupervisor is needed: under this role the bean does not exist,
 * which is exactly what the first assertion checks.
 */
@SpringBootTest(properties = ["demo.role=worker"])
@ActiveProfiles("test")
class WorkerProfileContextTest {

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `the worker role starts without the API-only beans`() {
        listOf(
            StatusCollector::class.java,
            SimulationRunner::class.java,
            WorkerSupervisor::class.java,
        ).forEach { type ->
            assertEquals(
                0,
                ctx.getBeanNamesForType(type).size,
                "${type.simpleName} must not run inside a worker process",
            )
        }
    }

    @Test
    fun `the controllers that depend on those beans drop out with them`() {
        // Both are @ConditionalOnBean on a primary-only bean. If either were left ungated,
        // the context above would already have failed to start.
        assertEquals(0, ctx.getBeanNamesForType(SimulationController::class.java).size)
        assertEquals(0, ctx.getBeanNamesForType(WorkerController::class.java).size)
    }

    @Test
    fun `a worker still has the activity beans and the payout controller`() {
        // The activity beans are what the process exists to run. PayoutController is not gated
        // and injects nothing primary-only, so it is present in this role too.
        assertTrue(ctx.getBeanNamesForType(ValidationActivitiesImpl::class.java).isNotEmpty())
        assertTrue(ctx.getBeanNamesForType(PayoutController::class.java).isNotEmpty())
    }
}
