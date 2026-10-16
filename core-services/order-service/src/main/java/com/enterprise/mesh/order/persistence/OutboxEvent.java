package com.enterprise.mesh.order.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * Transactional outbox row.
 *
 * <p>Written in the <em>same</em> transaction as the order, then relayed to Kafka
 * by a background poller. This is what makes "order persisted" and "event
 * published" atomic in effect: without it, a crash between the two would leave a
 * paid order that no fulfilment service ever hears about.
 *
 * <p>Relay is at-least-once, so consumers must be idempotent on {@code event_id}.
 *
 * <p><b>Read-only, deliberately.</b> {@code @Getter} only, no {@code @Setter}.
 * The relay's correctness depends on the three relay-owned columns being writable
 * only through the two methods that maintain them:
 *
 * <ul>
 *   <li>{@code publishedAt} is the leading column of
 *       {@code idx_outbox_unpublished}, which is the index the relay poller scans
 *       for work. A setter would let a caller mark an event published without
 *       clearing {@code lastError}, or set it out of band and have the poller skip
 *       an event that never reached Kafka - the precise failure the outbox exists
 *       to prevent.</li>
 *   <li>{@link #recordFailure} truncates {@code lastError} to 1023 characters so
 *       it fits its {@code varchar(1024)} column. The truncation lives in the
 *       method body and is gone the moment a setter exists, turning a long
 *       provider error into a failed relay rather than a recorded one.</li>
 *   <li>{@code attempts} is a tally incremented by {@code recordFailure}, not a
 *       value. {@link #markPublished} deliberately leaves it alone: the attempt
 *       count is what tells an operator how hard a poison event was retried.</li>
 * </ul>
 *
 * <p>{@code id} and {@code eventId} are mapped {@code updatable = false} and are
 * the keys consumers deduplicate on; {@code payload} is the serialised event and
 * {@code createdAt} its ordering key. {@link #isPublished()} stays a method: it is
 * derived from {@code publishedAt}, and there is no backing field for a getter to
 * collide with.
 */
@Getter
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
}
