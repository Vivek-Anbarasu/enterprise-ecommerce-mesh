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
 * <p>Written in the <em>same</em> transaction as the order. This is what makes
 * "order persisted" and "event published" atomic in effect: without it, a crash
 * between the two would leave a paid order that no fulfilment service ever hears
 * about.
 *
 * <p>Rows are inserted and never updated or deleted by the application. Debezium
 * tails the write-ahead log and publishes each insert to Kafka, so this table is
 * the durable hand-off point between the database transaction and the broker -
 * there is no poller holding a claim lock, and no {@code published_at},
 * {@code attempts} or {@code last_error} bookkeeping, because the WAL rather
 * than a column is the record of what was emitted.
 *
 * <p><b>Append-only, deliberately.</b> {@code @Getter} only, no {@code @Setter},
 * and no mutator methods: once a row exists it is immutable. That is what makes
 * the CDC contract safe. Debezium emits one event per insert, so a row that could
 * be updated after the fact would produce a second Kafka event for a change that
 * never happened, and downstream consumers - which are idempotent on
 * {@code event_id} rather than on "the latest state of the row" - would have no
 * way to tell the difference.
 *
 * <p>{@code id} and {@code eventId} are mapped {@code updatable = false} and are
 * the keys consumers deduplicate on; {@code payload} is the serialised event and
 * {@code createdAt} its ordering key.
 *
 * <p>Delivery is at-least-once, so consumers must be idempotent on
 * {@code event_id}.
 */
@Getter
@Entity
@Table(name = "outbox_events", indexes = {
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

    /**
     * The business event on its own, already serialised.
     *
     * <p>Deliberately not wrapped in a metadata envelope: Debezium publishes this
     * column verbatim as the Kafka record value, and consumers read that value as
     * the event. Every piece of metadata needed to rebuild the event header lives
     * in a sibling column above.
     */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
    }
}