package com.enterprise.mesh.notification;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Outbound notification channel.
 *
 * <p>An interface so the vendor (SendGrid, Twilio, Azure Communication
 * Services) can be swapped without touching the event handler. The shipped
 * implementation logs rather than sends: a worker that silently emailed real
 * addresses during testing would be a serious problem.
 */
public interface NotificationDispatcher {

    void dispatch(String customerId, String eventType, String orderId,
                  String totalAmount, String currency);

    /** Logging implementation; no external calls. */
    @Component
    @Slf4j
    class LoggingDispatcher implements NotificationDispatcher {

        @Override
        public void dispatch(String customerId, String eventType, String orderId,
                             String totalAmount, String currency) {
            String template = templateFor(eventType);
            log.info("[NOTIFY] customer={} template={} order={} amount={} {} payload={}",
                    customerId, template, orderId, totalAmount, currency,
                    Map.of("channel", "log", "eventType", eventType));
        }

        private String templateFor(String eventType) {
            return switch (eventType) {
                case "order.paid" -> "order-confirmation-email";
                case "order.shipped" -> "shipment-dispatch-email";
                case "order.delivered" -> "delivery-confirmation-email";
                case "order.failed", "order.cancelled" -> "order-problem-email";
                default -> "generic-transactional";
            };
        }
    }
}