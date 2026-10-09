package com.enterprise.mesh.order.saga;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.messaging.event.EventMetadata;
import com.enterprise.mesh.order.domain.Order;
import com.enterprise.mesh.order.persistence.OutboxEvent;
import com.enterprise.mesh.order.persistence.OutboxEventRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Records domain events for later publication.
 *
 * <p>Must be invoked from inside the transaction that writes the aggregate, so
 * the event and the state change commit or roll back together.
 *
 * <p>{@code REQUIRES_NEW} is deliberate: a failure to write the outbox row must
 * abort the whole order transaction rather than be swallowed. Propagating the
 * failure is what prevents an order existing with no corresponding event.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    /** Bounded so a bug cannot enqueue an unbounded payload. */
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String topic, Object payload, EventMetadata metadata) {
        String json = serialise(payload, metadata);
        outboxRepository.save(new OutboxEvent(
                UUID.randomUUID().toString(),
                metadata.eventId(),
                topic,
                metadata.eventType(),
                metadata.aggregateType(),
                metadata.aggregateId(),
                metadata.tenantId(),
                metadata.schemaVersion(),
                metadata.correlationId(),
                json));
        log.debug("Recorded outbox event type={} aggregate={}/{}",
                metadata.eventType(), metadata.aggregateType(), metadata.aggregateId());
    }

    /**
     * Serialises the payload together with its metadata envelope.
     *
     * <p>The envelope is embedded here (rather than only in Kafka headers) so the
     * outbox table is self-describing and can be replayed into a new topic after
     * an incident without needing the original header values.
     */
    private String serialise(Object payload, EventMetadata metadata) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            String envelope = objectMapper.writeValueAsString(new OutboxEnvelope(
                    metadata.eventId(),
                    metadata.eventType(),
                    metadata.schemaVersion(),
                    metadata.aggregateType(),
                    metadata.aggregateId(),
                    metadata.tenantId(),
                    metadata.occurredAt(),
                    metadata.correlationId(),
                    body));

            if (envelope.length() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Outbox payload exceeds " + MAX_PAYLOAD_BYTES
                        + " bytes for event type " + metadata.eventType());
            }
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Unable to serialise outbox payload for " + metadata.eventType(), e);
        }
    }

    /** Wire format persisted to the outbox table. */
    public record OutboxEnvelope(
            String eventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            String tenantId,
            Instant occurredAt,
            String correlationId,
            String payload) {
    }

    /** Convenience for the most common aggregate event. */
    public void recordOrderEvent(String topic, Object payload, Order order, String eventType,
                                 String correlationId) {
        EventMetadata metadata = EventMetadata.of(
                eventType, "order", order.getId(), order.getTenantId(),
                correlationId == null ? order.getCorrelationId() : correlationId);
        record(topic, payload, metadata);
    }

    /** Exposed for the relay's poison-row query. */
    @Transactional(readOnly = true)
    public List<OutboxEvent> findPoisoned(int threshold, int limit) {
        return outboxRepository.findPoisoned(threshold,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }
}
