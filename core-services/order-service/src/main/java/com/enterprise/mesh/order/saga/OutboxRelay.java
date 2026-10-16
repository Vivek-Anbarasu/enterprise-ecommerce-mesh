package com.enterprise.mesh.order.saga;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

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
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxStore outboxStore;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    // Both are injected through @RequiredArgsConstructor. The root
    // lombok.config copies @Value onto the generated constructor parameters;
    // without that, Spring sees two unannotated int parameters and fails to
    // start with no candidate bean. The annotation has to be on the field.
    @Value("${mesh.outbox.batch-size:100}")
    private final int batchSize;
    @Value("${mesh.outbox.max-attempts:10}")
    private final int maxAttempts;
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