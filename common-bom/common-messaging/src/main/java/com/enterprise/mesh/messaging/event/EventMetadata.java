package com.enterprise.mesh.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event metadata carried in Kafka headers (CloudEvents binary content mode).
 *
 * <p>The Kafka <em>value</em> is the strongly-typed payload; this metadata rides
 * alongside it in headers. That combination gives us both type safety on the
 * consumer side and the routing/observability metadata that a nested envelope
 * would provide, without relying on generic type resolution at deserialization
 * time.
 *
 * @param eventId       unique id; also the consumer-side idempotency key
 * @param eventType     logical event name, e.g. {@code order.created}
 * @param aggregateType owning aggregate, e.g. {@code order}
 * @param aggregateId   id of the owning aggregate instance
 * @param tenantId      owning tenant
 * @param schemaVersion payload schema version, incremented on breaking change
 * @param occurredAt    when the change happened in the owning service
 * @param correlationId cross-service trace identifier
 * @param causationId   id of the event/message that directly caused this one
 */
public record EventMetadata(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String tenantId,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        String causationId) {

    public static EventMetadata of(String eventType, String aggregateType, String aggregateId,
                                   String tenantId, String correlationId) {
        return new EventMetadata(
                UUID.randomUUID().toString(),
                eventType,
                aggregateType,
                aggregateId,
                tenantId,
                1,
                Instant.now(),
                correlationId,
                null);
    }

    public EventMetadata withCausationId(String newCausationId) {
        return new EventMetadata(eventId, eventType, aggregateType, aggregateId, tenantId,
                schemaVersion, occurredAt, correlationId, newCausationId);
    }

    /** Next schema version, used when a payload changes incompatibly. */
    public EventMetadata nextSchemaVersion() {
        return new EventMetadata(eventId, eventType, aggregateType, aggregateId, tenantId,
                schemaVersion + 1, occurredAt, correlationId, causationId);
    }
}
