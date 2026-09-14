package com.example.payouts

import com.example.payouts.worker.WorkerSupervisor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import kotlin.test.assertTrue

/**
 * Caddy owns the origin and gives Temporal Web the catch-all. The demo namespaces itself to
 * exactly three prefixes -- the root, everything under `/assets`, and everything under
 * `/demo-api`. Those reach Spring Boot; every other path is answered by Temporal Web.
 *
 * A route added outside those prefixes answers on :8081 but is served by Temporal Web on
 * :8080, which is the port the assembled stack exposes. No other test here covers that.
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteNamespaceTest {

    @MockitoBean
    private lateinit var workerSupervisor: WorkerSupervisor

    // Qualified because the actuator contributes a second RequestMappingHandlerMapping
    // (controllerEndpointHandlerMapping). This one holds the application's own controllers.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var environment: Environment

    @Test
    fun `every mapped route stays inside a prefix Caddy sends to Spring`() {
        val patterns = handlerMapping.handlerMethods.keys
            .flatMap { info ->
                info.pathPatternsCondition?.patternValues
                    ?: info.patternsCondition?.patterns
                    ?: emptySet()
            }
            .distinct()
            .sorted()

        assertTrue(patterns.isNotEmpty(), "no routes were discovered, so this test proves nothing")

        val stray = patterns.filterNot { pattern ->
            pattern == "/" || pattern.startsWith("/demo-api") || pattern.startsWith("/assets")
        }

        assertTrue(
            stray.isEmpty(),
            "these routes fall outside the prefixes Caddy forwards to Spring, so Temporal Web " +
                "would answer them in the assembled stack: $stray",
        )
    }

    @Test
    fun `the actuator and the error page are namespaced under demo-api`() {
        // Both are relocated in application.yml for the same reason. If either drifts back
        // to its default path it disappears behind Temporal's catch-all -- and the Grafana
        // and Prometheus wiring scrapes the actuator through Caddy.
        val actuatorBase = environment.getProperty("management.endpoints.web.base-path")
        assertTrue(
            actuatorBase != null && actuatorBase.startsWith("/demo-api"),
            "actuator base-path must live under /demo-api, was '$actuatorBase'",
        )

        val errorPath = environment.getProperty("server.error.path")
        assertTrue(
            errorPath != null && errorPath.startsWith("/demo-api"),
            "server.error.path must live under /demo-api, was '$errorPath'",
        )
    }
}
