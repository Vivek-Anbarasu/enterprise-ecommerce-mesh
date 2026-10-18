package com.enterprise.mesh.order.saga;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

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
 * the event and the state change commit or roll back together. A failure to write
 * the outbox row aborts the whole order transaction rather than being swallowed,
 * which is what prevents an order existing with no corresponding event.
 *
 * <p>Rows are never read back by this service. Delivery is Debezium's job: it
 * tails the PostgreSQL write-ahead log and publishes every inserted row to Kafka,
 * so there is no polling loop and no relay to fall behind. That means no
 * {@code published_at}/{@code attempts} bookkeeping - the WAL, not this service,
 * is the record of what was emitted, and CDC lag is a Debezium-side metric.
 *
 * <p><b>What lands in the {@code payload} column is a contract, not an
 * implementation detail.</b> It must be the bare business event, because
 * Debezium's outbox event router publishes that column verbatim as the Kafka
 * record value, and every consumer in the mesh reads the record value as the
 * event itself. Routing and metadata travel in sibling columns that the router
 * maps onto the {@code ce_*} headers; wrapping the payload in another JSON
 * envelope would break all of them at once, and silently, because a consumer
 * handed the wrong shape finds its fields missing rather than being told it is
 * wrong.
 *
 * <p>Delivery is at-least-once, so consumers must remain idempotent on the
 * event id.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

    /** Bounded so a bug cannot enqueue an unbounded payload. */
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String topic, Object payload, EventMetadata metadata) {
        String json = serialise(payload);
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
     * Serialises the business payload on its own.
     *
     * <p>The {@code payload} column holds the event body and nothing else. The
     * routing and metadata that used to be wrapped around it as a JSON envelope
     * already exist as real columns - {@code topic}, {@code event_id},
     * {@code event_type}, {@code aggregate_type}, {@code aggregate_id},
     * {@code tenant_id}, {@code schema_version}, {@code correlation_id} - and
     * Debezium builds the Kafka envelope from those columns when it maps the row
     * onto headers and the record key.
     *
     * <p>So the table is self-describing without a nested copy, and embedding one
     * would actively break delivery: the outbox event router passes the column
     * value through unmodified as the Kafka record value, so an envelope here
     * would reach consumers with the real event buried inside it as an escaped
     * JSON string. Consumers read the body as the event directly, so wrapping it
     * would turn every event into an unparseable one.
     */
    private String serialise(Object payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);

            if (body.length() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Outbox payload exceeds " + MAX_PAYLOAD_BYTES
                        + " bytes for event type " + payload.getClass().getSimpleName());
            }
            return body;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise outbox payload", e);
        }
    }

    /** Convenience for the most common aggregate event. */
    public void recordOrderEvent(String topic, Object payload, Order order, String eventType,
                                 String correlationId) {
        EventMetadata metadata = EventMetadata.of(
                eventType, "order", order.getId(), order.getTenantId(),
                correlationId == null ? order.getCorrelationId() : correlationId);
        record(topic, payload, metadata);
    }
}