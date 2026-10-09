package com.enterprise.mesh.observability;

import jakarta.servlet.Filter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

import com.enterprise.mesh.observability.web.CorrelationIdFilter;

/**
 * Servlet-only observability wiring.
 *
 * <p>Kept in its own auto-configuration rather than alongside the metric
 * configuration because Spring introspects every declared method of a
 * configuration class while evaluating conditions. If
 * {@link CorrelationIdFilter} were a method on a class that a headless worker
 * loads, the missing {@code jakarta.servlet.Filter} type would raise
 * {@code NoClassDefFoundError} during that introspection - before the
 * method-level {@code @ConditionalOnWebApplication} guard could be evaluated.
 *
 * <p>Declaring the class itself behind {@code @ConditionalOnClass} means Spring
 * skips loading it entirely when the servlet API is absent, which is what lets
 * Kafka workers run without a web stack.
 */
@AutoConfiguration
@ConditionalOnClass(Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServletObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
