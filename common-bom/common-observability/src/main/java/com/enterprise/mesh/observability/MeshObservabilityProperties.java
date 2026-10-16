package com.enterprise.mesh.observability;

import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mesh-level observability configuration.
 *
 * <p>Note that the underlying actuator/tracing behaviour is configured through
 * Boot's own {@code management.*} and {@code management.tracing.*} namespaces;
 * this class only carries settings the mesh itself must read.
 *
 * <p>Accessors are generated. {@code @Setter} deliberately produces nothing for
 * the {@code final business} field - it is always present and is mutated through
 * its own setters, never replaced. {@code BusinessMetrics} is annotated
 * separately and independently; class-level annotations do not cascade to a
 * nested class, and omitting it breaks binding of {@code mesh.business.domains}
 * silently rather than at compile time.
 */
@ConfigurationProperties(prefix = "mesh")
@Getter
@Setter
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

    @Getter
    @Setter
    public static class BusinessMetrics {
        private List<String> domains = List.of("orders", "payments", "inventory", "catalog");
    }
}
