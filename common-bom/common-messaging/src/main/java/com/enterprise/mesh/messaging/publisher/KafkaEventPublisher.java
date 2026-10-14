package com.enterprise.mesh.messaging.publisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

import com.enterprise.mesh.messaging.event.CloudEventHeaders;
import com.enterprise.mesh.messaging.event.EventMetadata;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.Message;

/**
 * Publishes domain events to Kafka.
 *
 * <p>Delivery is at-least-once: a successful {@code send()} means the record was
 * acknowledged by the broker's configured {@code acks} level, not that a
 * consumer has processed it. Consumers must therefore be idempotent, keyed on
 * {@link EventMetadata#eventId()}.
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Override
    public CompletableFuture<Void> publish(String topic, Object payload, EventMetadata metadata) {
        Message<Object> message = CloudEventHeaders.toMessage(payload, metadata);

        log.debug("Publishing event type={} aggregate={}/{} topic={} eventId={}",
                metadata.eventType(), metadata.aggregateType(), metadata.aggregateId(),
                topic, metadata.eventId());

        return kafkaTemplate.send(topic, message)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        // Do not throw: the caller is usually inside a DB
                        // transaction and cannot usefully react here. Persistence
                        // guarantees belong to the outbox, not to this call.
                        log.error("Failed to publish event topic={} eventId={} type={}: {}",
                                topic, metadata.eventId(), metadata.eventType(),
                                error.toString());
                    } else {
                        log.debug("Published event topic={} partition={} offset={} eventId={}",
                                topic,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                metadata.eventId());
                    }
                })
                // KafkaTemplate yields SendResult; the caller only cares that the
                // broker acknowledged, so collapse it to Void.
                .thenApply(result -> null);
    }
}