package com.enterprise.mesh.observability;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mesh-level observability configuration.
 *
 * <p>Note that the underlying actuator/tracing behaviour is configured through
 * Boot's own {@code management.*} and {@code management.tracing.*} namespaces;
 * this class only carries settings the mesh itself must read.
 */
@ConfigurationProperties(prefix = "mesh")
public class MeshObservabilityProperties {

    /** Environment label added to every metric, e.g. {@code prod}, {@code staging}. */
    private String environment = "local";

    /** Service tier used for SLO dashboards, e.g. {@code edge}, {@code core}. */
    private String tier = "core";

    /**
     * Maximum number of distinct series allowed per meter name. Protects the
     * metrics backend from unbounded tag cardinality (e.g. tagging a metric
     * with an order id). Raise deliberately, never to "fix" a missing series.
     */
    private int maxMeters = 10_000;

    /** Emit one counter per business outcome for the domain dashboards. */
    private final BusinessMetrics business = new BusinessMetrics();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public int getMaxMeters() {
        return maxMeters;
    }

    public void setMaxMeters(int maxMeters) {
        this.maxMeters = maxMeters;
    }

    public BusinessMetrics getBusiness() {
        return business;
    }

    public static class BusinessMetrics {
        private List<String> domains = List.of("orders", "payments", "inventory", "catalog");

        public List<String> getDomains() {
            return domains;
        }

        public void setDomains(List<String> domains) {
            this.domains = domains;
        }
    }
}
