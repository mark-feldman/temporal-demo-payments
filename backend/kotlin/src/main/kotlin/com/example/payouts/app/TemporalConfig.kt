package com.example.payouts.app

import com.uber.m3.tally.RootScopeBuilder
import com.uber.m3.tally.Scope
import com.uber.m3.util.Duration as TallyDuration
import io.micrometer.core.instrument.MeterRegistry
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.reporter.MicrometerClientStatsReporter
import org.springframework.context.annotation.Bean
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter

@Configuration
class TemporalConfig {

    /**
     * Spring auto-registers a kotlinx.serialization HTTP converter for @Serializable types,
     * and its default Json omits any field still holding its declared default. That quietly
     * dropped `approvalTier: NONE` and `failureCategory: NONE` from API responses, and the UI
     * read the absent field as undefined. An API contract should not change shape based on
     * the values in it, so encodeDefaults is forced on.
     */
    @Bean
    fun jsonHttpConverter(): KotlinSerializationJsonHttpMessageConverter =
        KotlinSerializationJsonHttpMessageConverter(
            Json { encodeDefaults = true; ignoreUnknownKeys = true },
        )

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
