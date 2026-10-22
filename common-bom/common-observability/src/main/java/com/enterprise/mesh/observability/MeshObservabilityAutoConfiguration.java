package com.enterprise.mesh.observability;

import lombok.extern.slf4j.Slf4j;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers a small set of metrics/logging defaults shared by every service:
 * correlation ids, bounded-cardinality common tags, and rejection of accidental
 * high-cardinality tag explosions.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(MeshObservabilityProperties.class)
@Slf4j
public class MeshObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BusinessMetrics businessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }

    /**
     * Trace-id lookup for error responses. Registered here so every service gets
     * the same bean, and so a service with tracing disabled still gets one that
     * correctly reports "no trace" instead of a placeholder.
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceIds traceIds(ObjectProvider<Tracer> tracerProvider) {
        return new TraceIds(tracerProvider);
    }

    /**
     * Adds the mesh's common tags to every registry.
     *
     * <p>Implemented as a {@link MeterRegistryCustomizer} rather than a
     * {@code MeterFilter} bean that injects {@code MeterRegistry}. The filter
     * approach creates a bean cycle - the Prometheus registry needs its filters
     * to finish construction, so a filter that itself depends on the registry
     * cannot be created. Boot applies customizers from outside that cycle, which
     * is exactly the seam intended for global registry configuration.
     */
    @Bean
    public MeterRegistryCustomizer<PrometheusMeterRegistry> meshCommonTags(
            Environment environment,
            MeshObservabilityProperties properties,
            @Value("${spring.application.name:unknown-service}") String applicationName) {

        log.info("Configured common metric tags for application={}", applicationName);
        return registry -> registry.config().commonTags(
                "application", applicationName,
                "environment", resolveEnvironment(environment, properties),
                "tier", properties.getTier());
    }

    /**
     * Refuses meter names that Prometheus cannot represent. Prometheus silently
     * drops such series on scrape, so denying them here surfaces the bug during
     * development instead of as a mysteriously empty dashboard.
     */
    @Bean
    public MeterFilter meshMeterNameGuard() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                String name = id.getName();
                if (name == null || !name.matches("[a-zA-Z_:][a-zA-Z0-9_:.]*")) {
                    log.warn("Denying meter with Prometheus-incompatible name: {}", name);
                    return MeterFilterReply.DENY;
                }
                return MeterFilterReply.NEUTRAL;
            }
        };
    }

    /**
     * Caps total series per meter. An unbounded tag value (a raw order id, an
     * email address) is the classic cause of a metrics backend falling over in
     * production; this makes the failure mode a dropped series instead.
     */
    @Bean
    public MeterFilter meshCardinalityGuard(MeshObservabilityProperties properties) {
        int limit = properties.getMaxMeters();
        log.info("Meter cardinality limited to {} series per meter name", limit);
        return MeterFilter.maximumAllowableMetrics(limit);
    }

    private String resolveEnvironment(Environment environment, MeshObservabilityProperties properties) {
        // Prefer an explicit mesh.environment, then standard Spring profile names,
        // and only then the property default.
        String configured = environment.getProperty("mesh.environment");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 ? profiles[0] : properties.getEnvironment();
    }
}