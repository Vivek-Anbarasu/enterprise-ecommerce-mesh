package com.enterprise.mesh.order.saga;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.messaging.event.EventMetadata;
import com.enterprise.mesh.messaging.publisher.EventPublisher;
import com.enterprise.mesh.order.persistence.OutboxEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Relays outbox rows to Kafka.
 *
 * <p>Pattern per batch: claim under a row lock in a short transaction, publish
 * each event outside any transaction, then mark published in another short
 * transaction. The publish deliberately does <em>not</em> hold the row lock,
 * because holding a database transaction open across a network call to the
 * broker is how a connection pool collapses during a broker outage.
 *
 * <p>Delivery is at-least-once: a crash between publish and mark-published
 * causes redelivery, so consumers must be idempotent on the event id.
 */
@Component
@ConditionalOnProperty(prefix = "mesh.outbox", name = "relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore outboxStore;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxStore outboxStore,
                       EventPublisher eventPublisher,
                       ObjectMapper objectMapper,
                       @Value("${mesh.outbox.batch-size:100}") int batchSize,
                       @Value("${mesh.outbox.max-attempts:10}") int maxAttempts) {
        this.outboxStore = outboxStore;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${mesh.outbox.poll-interval-ms:1000}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxStore.claimBatch(batchSize);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Relaying {} outbox event(s)", batch.size());
        batch.forEach(this::publishOne);
    }

    private void publishOne(OutboxEvent claimed) {
        if (claimed.getAttempts() >= maxAttempts) {
            // Poison row: stop retrying so it cannot starve healthy events. It
            // stays visible for the outbox_dead_letter_total alert.
            log.error("Outbox event {} exceeded {} attempts; leaving for manual replay. lastError={}",
                    claimed.getId(), maxAttempts, claimed.getLastError());
            return;
        }

        try {
            OutboxService.OutboxEnvelope envelope =
                    objectMapper.readValue(claimed.getPayload(), OutboxService.OutboxEnvelope.class);

            EventMetadata metadata = new EventMetadata(
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.aggregateType(),
                    envelope.aggregateId(),
                    envelope.tenantId(),
                    envelope.schemaVersion(),
                    envelope.occurredAt() == null ? Instant.now() : envelope.occurredAt(),
                    envelope.correlationId(),
                    null);

            Object payload = objectMapper.readValue(envelope.payload(), Object.class);

            eventPublisher.publish(claimed.getTopic(), payload, metadata)
                    .thenRun(() -> outboxStore.markPublished(claimed.getId()))
                    .exceptionally(error -> {
                        log.warn("Publish failed for outbox {}: {}", claimed.getId(), error.toString());
                        outboxStore.markFailed(claimed.getId(), error.toString());
                        return null;
                    });
        } catch (Exception e) {
            // Serialisation failures will never succeed on retry, but they are
            // still counted so the poison threshold eventually stops them.
            log.error("Failed to relay outbox event {}: {}", claimed.getId(), e.toString());
            outboxStore.markFailed(claimed.getId(), e.toString());
        }
    }
}
