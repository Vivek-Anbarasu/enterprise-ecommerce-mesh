package com.enterprise.mesh.order.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Transactional outbox row.
 *
 * <p>Written in the <em>same</em> transaction as the order, then relayed to Kafka
 * by a background poller. This is what makes "order persisted" and "event
 * published" atomic in effect: without it, a crash between the two would leave a
 * paid order that no fulfilment service ever hears about.
 *
 * <p>Relay is at-least-once, so consumers must be idempotent on {@code event_id}.
 */
@Entity
@Table(name = "outbox_events", indexes = {
        @Index(name = "idx_outbox_unpublished", columnList = "published_at,id"),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregate_id")
})
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** JSON payload, already serialised. */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    protected OutboxEvent() {
        // required by JPA
    }

    public OutboxEvent(String id, String eventId, String topic, String eventType,
                        String aggregateType, String aggregateId, String tenantId,
                        int schemaVersion, String correlationId, String payload) {
        this.id = id;
        this.eventId = eventId;
        this.topic = topic;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.tenantId = tenantId;
        this.schemaVersion = schemaVersion;
        this.correlationId = correlationId;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.attempts = 0;
    }

    public void markPublished(Instant when) {
        this.publishedAt = when;
        this.lastError = null;
    }

    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = error == null ? "unknown" : error.substring(0, Math.min(1023, error.length()));
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
