package com.enterprise.mesh.messaging.config;

import java.time.Duration;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mesh-wide messaging tunables. Bootstrap servers still come from
 * {@code spring.kafka.bootstrap-servers} so standard Spring tooling and
 * tooling/actuators keep working unchanged.
 *
 * <p>Accessors are generated. These are bound by JavaBean binding, so the public
 * setters are the binding contract rather than incidental surface: Spring Boot's
 * relaxed binding calls them directly to apply {@code mesh.messaging.*}.
 */
@ConfigurationProperties(prefix = "mesh.messaging")
@Getter
@Setter
public class MeshMessagingProperties {

    /**
     * Create topics on startup when the broker reports them missing.
     * Intended for local development and first-cluster bootstrap only.
     */
    private boolean autoCreateTopics = false;

    private int partitions = 12;

    /** Replication factor. Must be <= number of brokers; use 3 for production. */
    private short replicas = 3;

    private Map<String, String> topicConfigs = Map.of(
            "cleanup.policy", "delete",
            "retention.ms", "604800000",   // 7 days
            "min.insync.replicas", "2",
            "segment.bytes", "268435456");  // 256 MiB

    /** In-flight consumer threads per listener container. */
    private int consumerConcurrency = 3;

    private int maxPollRecords = 100;

    /** Delay between delivery attempts before the DLQ. */
    private Duration retryInterval = Duration.ofSeconds(2);

    /** Attempts after the first failure before diverting to the DLQ. */
    private long maxRetries = 3;

    private Duration deliveryTimeout = Duration.ofSeconds(30);

    private Duration requestTimeout = Duration.ofSeconds(10);

    /** Pause between consumer polls; gives the pod a chance to breathe. */
    private Duration idleBetweenPolls = Duration.ofMillis(100);
}
