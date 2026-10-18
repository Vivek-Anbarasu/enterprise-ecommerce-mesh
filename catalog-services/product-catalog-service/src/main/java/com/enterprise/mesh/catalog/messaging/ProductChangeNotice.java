package com.enterprise.mesh.catalog.messaging;

/**
 * In-process Spring event announcing a catalogue change, before it becomes a Kafka record.
 *
 * <p>Exists so the event type can travel with the payload. {@code @TransactionalEventListener}
 * matches on a single event class, so a two-argument listener
 * ({@code (ProductChangedEvent, String eventType)}) is not expressible - the framework would
 * have nothing to match against. Wrapping the pair is the supported way to carry both.
 *
 * <p>This is an internal signal and never crosses the wire. What reaches Kafka is the
 * {@link ProductChangedEvent} payload, with the type in the CloudEvents headers.
 *
 * @param eventType one of {@link ProductEventPublisher#CREATED}, {@link ProductEventPublisher#UPDATED},
 *                  {@link ProductEventPublisher#DEACTIVATED}
 */
public record ProductChangeNotice(String eventType, ProductChangedEvent product) {

    public ProductChangeNotice {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (product == null) {
            throw new IllegalArgumentException("product payload is required");
        }
    }
}