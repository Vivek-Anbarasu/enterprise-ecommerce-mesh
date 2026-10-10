package com.enterprise.mesh.shipping;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.CloudEventHeaders;
import com.enterprise.mesh.shipping.domain.Shipment;
import com.enterprise.mesh.shipping.domain.Shipment.ShipmentStatus;

/**
 * Turns order events into shipments.
 *
 * <p>Reads the raw {@link Message} so that new producer-side fields are ignored
 * rather than causing a deserialization failure and dead-lettering a message
 * that is otherwise perfectly processable.
 */
@Component
public class OrderFulfilmentListener {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfilmentListener.class);

    private final ShippingService shippingService;

    public OrderFulfilmentListener(ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    /**
     * Consumes {@code order.paid} and creates a shipment.
     *
     * <p>Scoped to the single event type rather than the whole topic: consuming
     * every order event and filtering in code would waste partitions and risk
     * creating parcels for orders that were never paid.
     */
    @KafkaListener(
            topics = MessagingTopics.ORDER_EVENTS,
            groupId = "${mesh.shipping.consumer-group:shipping-group}",
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onOrderPaid(Message<Map<String, Object>> message) {
        var metadata = CloudEventHeaders.read(message);

        if (!"order.paid".equals(metadata.eventType())) {
            log.debug("Ignoring event type={} for fulfilment", metadata.eventType());
            return;
        }

        Map<String, Object> payload = message.getPayload();
        String orderId = string(payload, "orderId", metadata.aggregateId());
        String customerId = string(payload, "customerId", null);

        if (orderId == null || customerId == null) {
            // Malformed payload: log and let the error handler dead-letter it
            // rather than creating a shipment we cannot attribute.
            log.error("order.paid event {} missing orderId/customerId; cannot fulfil", orderId);
            return;
        }

        try (MDC.MDCCloseable _ = MDC.putCloseable("event_id", metadata.eventId())) {
            shippingService.createShipment(orderId, metadata.tenantId(), customerId,
                    "address-on-file", "standard", metadata.eventId());
        }
    }

    private String string(Map<String, Object> payload, String key, String fallback) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
