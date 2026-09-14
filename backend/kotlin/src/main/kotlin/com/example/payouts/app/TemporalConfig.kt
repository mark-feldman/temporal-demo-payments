package com.example.payouts.app

import com.uber.m3.tally.RootScopeBuilder
import com.uber.m3.tally.Scope
import com.uber.m3.util.Duration as TallyDuration
import io.micrometer.core.instrument.MeterRegistry
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.reporter.MicrometerClientStatsReporter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TemporalConfig {

    /**
     * Named `mainDataConverter` deliberately: the Spring Boot starter fails on ambiguity
     * when more than one DataConverter bean exists and resolves the primary by this name.
     */
    @Bean
    fun mainDataConverter(): DataConverter =
        DefaultDataConverter.newDefaultInstance()
            .withPayloadConverterOverrides(KotlinxJsonPayloadConverter())

    /**
     * SDK metrics into the same Micrometer registry the business counters use, so a single
     * Prometheus scrape of /demo-api/actuator/prometheus returns temporal_* and payout_*
     * together.
     *
     * This overrides the starter's own MetricsScopeAutoConfiguration bean of the same name
     * (hence spring.main.allow-bean-definition-overriding), so that the report interval is
     * explicit and short enough to watch during a demo.
     */
    @Bean(name = ["temporalMetricsScope"], destroyMethod = "close")
    fun temporalMetricsScope(registry: MeterRegistry): Scope =
        RootScopeBuilder()
            .reporter(MicrometerClientStatsReporter(registry))
            .reportEvery(TallyDuration.ofSeconds(1.0))
}
