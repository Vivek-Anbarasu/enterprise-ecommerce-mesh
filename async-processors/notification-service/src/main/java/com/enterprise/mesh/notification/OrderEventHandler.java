package com.enterprise.mesh.notification;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Fan-out on order lifecycle events.
 *
 * <p>Idempotency is the critical property: Kafka delivers at-least-once, so the
 * same {@code order.paid} event can arrive twice. Without the processed-event
 * guard a shopper would receive duplicate "your order is confirmed" emails,
 * which is both a support burden and a compliance problem (CASL requires one
 * commercial electronic message per consent).
 */
@Service
public class OrderEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderEventHandler.class);

    /** Redis key prefix for the de-duplication window. */
    private static final String DEDUPE_PREFIX = "notified:";

    private final StringRedisTemplate redis;
    private final NotificationDispatcher dispatcher;

    public OrderEventHandler(StringRedisTemplate redis, NotificationDispatcher dispatcher) {
        this.redis = redis;
        this.dispatcher = dispatcher;
    }

    /**
     * @param eventId     used for de-duplication
     * @param orderId     aggregate the notification refers to
     * @param customerId  recipient
     * @param eventType   e.g. {@code order.paid} or {@code order.shipped}
     * @param totalAmount formatted total, or null for non-financial events
     */
    public void onOrderEvent(String eventId, String orderId, String customerId,
                             String eventType, String totalAmount, String currency) {
        if (alreadyProcessed(eventId)) {
            log.info("Skipping duplicate notification for eventId={} type={}", eventId, eventType);
            return;
        }

        try {
            dispatcher.dispatch(customerId, eventType, orderId, totalAmount, currency);
            markProcessed(eventId);
            log.info("Dispatched notification type={} order={} customer={}",
                    eventType, orderId, customerId);
        } catch (RuntimeException e) {
            // Do NOT mark as processed: the event must be retried. Letting the
            // exception propagate sends it to the DLQ after the configured retries.
            log.error("Failed to dispatch notification for event {}: {}", eventId, e.toString());
            throw e;
        }
    }

    /**
     * Best-effort de-duplication. A Redis outage degrades to possible duplicate
     * sends rather than dropping customer notifications, which is the correct
     * trade-off in this direction.
     */
    private boolean alreadyProcessed(String eventId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(DEDUPE_PREFIX + eventId));
        } catch (RuntimeException e) {
            log.warn("Dedupe check unavailable for {}; proceeding: {}", eventId, e.toString());
            return false;
        }
    }

    private void markProcessed(String eventId) {
        try {
            // Window comfortably exceeds the Kafka retention so a replay inside
            // the same window is still suppressed.
            redis.opsForValue().set(DEDUPE_PREFIX + eventId, "1",
                    java.time.Duration.ofDays(8));
        } catch (RuntimeException e) {
            log.warn("Could not record dedupe marker for {}: {}", eventId, e.toString());
        }
    }
}
