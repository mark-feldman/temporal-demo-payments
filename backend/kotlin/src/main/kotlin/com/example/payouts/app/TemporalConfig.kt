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
     * and its default Json omits any field still holding its declared default. encodeDefaults
     * is forced on so the response shape does not change with the values in it.
     */
    @Bean
    fun jsonHttpConverter(): KotlinSerializationJsonHttpMessageConverter =
        KotlinSerializationJsonHttpMessageConverter(
            Json { encodeDefaults = true; ignoreUnknownKeys = true },
        )

    /**
     * Named `mainDataConverter`: the Spring Boot starter resolves the primary DataConverter by
     * this name and fails on ambiguity when more than one such bean exists.
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
     * (hence spring.main.allow-bean-definition-overriding) so the report interval is explicit.
     */
    @Bean(name = ["temporalMetricsScope"], destroyMethod = "close")
    fun temporalMetricsScope(registry: MeterRegistry): Scope =
        RootScopeBuilder()
            .reporter(MicrometerClientStatsReporter(registry))
            .reportEvery(TallyDuration.ofSeconds(1.0))
}
