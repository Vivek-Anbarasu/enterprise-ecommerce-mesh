package com.enterprise.mesh.notification.messaging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import org.slf4j.MDC;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.CloudEventHeaders;
import com.enterprise.mesh.notification.service.OrderEventHandler;

/**
 * Consumes order lifecycle events and triggers customer notifications.
 *
 * <p>The listener takes the raw {@link Message} rather than a typed DTO so it can
 * read the CloudEvents metadata headers directly. That keeps this consumer
 * working across payload schema evolution: a new field on the producer side is
 * ignored here instead of failing deserialization and dead-lettering a message
 * that is perfectly readable.
 *
 * <p>Idempotency is delegated to {@link OrderEventHandler}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderEventHandler handler;
    @KafkaListener(
            topics = MessagingTopics.ORDER_EVENTS,
            groupId = "${mesh.notification.consumer-group:notification-group}")
    public void onOrderEvent(Message<Map<String, Object>> message) {
        var metadata = CloudEventHeaders.read(message);
        Map<String, Object> payload = message.getPayload();

        // Carry the trace and correlation ids into the handler's logs.
        try (MDC.MDCCloseable _ = MDC.putCloseable("event_id", metadata.eventId());
             MDC.MDCCloseable _ = MDC.putCloseable("tenant_id", metadata.tenantId())) {

            log.debug("Received event type={} aggregate={} partition={} offset={}",
                    metadata.eventType(), metadata.aggregateId(),
                    message.getHeaders().get(
                            org.springframework.kafka.support.KafkaHeaders.RECEIVED_PARTITION),
                    message.getHeaders().get(
                            org.springframework.kafka.support.KafkaHeaders.OFFSET));

            handler.onOrderEvent(
                    metadata.eventId(),
                    string(payload, "orderId", metadata.aggregateId()),
                    string(payload, "customerId", null),
                    metadata.eventType(),
                    string(payload, "totalAmount", null),
                    string(payload, "currency", null));
        }
    }

    private String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}