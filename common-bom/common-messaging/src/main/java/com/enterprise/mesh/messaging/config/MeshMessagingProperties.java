package com.enterprise.mesh.messaging.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mesh-wide messaging tunables. Bootstrap servers still come from
 * {@code spring.kafka.bootstrap-servers} so standard Spring tooling and
 * tooling/actuators keep working unchanged.
 */
@ConfigurationProperties(prefix = "mesh.messaging")
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

    public boolean isAutoCreateTopics() {
        return autoCreateTopics;
    }

    public void setAutoCreateTopics(boolean autoCreateTopics) {
        this.autoCreateTopics = autoCreateTopics;
    }

    public int getPartitions() {
        return partitions;
    }

    public void setPartitions(int partitions) {
        this.partitions = partitions;
    }

    public short getReplicas() {
        return replicas;
    }

    public void setReplicas(short replicas) {
        this.replicas = replicas;
    }

    public Map<String, String> getTopicConfigs() {
        return topicConfigs;
    }

    public void setTopicConfigs(Map<String, String> topicConfigs) {
        this.topicConfigs = topicConfigs;
    }

    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = consumerConcurrency;
    }

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval;
    }

    public long getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(long maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getDeliveryTimeout() {
        return deliveryTimeout;
    }

    public void setDeliveryTimeout(Duration deliveryTimeout) {
        this.deliveryTimeout = deliveryTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getIdleBetweenPolls() {
        return idleBetweenPolls;
    }

    public void setIdleBetweenPolls(Duration idleBetweenPolls) {
        this.idleBetweenPolls = idleBetweenPolls;
    }
}
