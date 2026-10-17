package com.enterprise.mesh.ai.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.enterprise.mesh.ai.service.RecommendationService;
import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.CloudEventHeaders;

import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * Turns purchases into affinity weights, from the order event rather than from the
 * browser.
 *
 * <p>This is the half of interaction recording that must not be client-asserted.
 * {@code order.paid} is written by order-service in the same transaction as the order
 * and relayed by its outbox, so it is authoritative by construction. The browser can
 * lie about what it rendered; it cannot lie about a payment that was authorised.
 * Purchases also carry the heaviest weight in the model (8, against 1 for a view), so
 * having them depend on the least reliable source would have inverted the whole
 * ranking.
 *
 * <p>Views stay on the REST endpoint, because nothing else in the mesh observes a page
 * being rendered. They are weighted at 1 for exactly that reason.
 *
 * <p><b>Idempotency is not optional here.</b> Kafka delivers at-least-once, so the same
 * {@code order.paid} can arrive twice, and the second arrival would add 8 to every
 * category in the order - permanently, because scores decay by only 15% per subsequent
 * interaction. The dedupe marker follows the same Redis pattern notification-service
 * uses, and on failure it deliberately errs towards recording the purchase rather than
 * dropping it: a duplicated purchase signal is recoverable by expiring the profile, a
 * silently discarded one is not.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    /**
     * Redis key prefix for the de-duplication window. Namespaced by consumer so two
     * services watching the same topic keep independent windows.
     */
    private static final String DEDUPE_PREFIX = "ai:processed:";

    /**
     * Window comfortably longer than Kafka retention, so a replay inside it is still
     * suppressed.
     */
    private static final java.time.Duration DEDUPE_WINDOW = java.time.Duration.ofDays(8);

    /** Only order.paid carries a completed purchase. order.failed must not score. */
    private static final String ORDER_PAID = "order.paid";

    private final RecommendationService recommendationService;
    private final StringRedisTemplate redis;

    @KafkaListener(
            topics = MessagingTopics.ORDER_EVENTS,
            groupId = "${mesh.ai.consumer-group:ai-orchestrator}")
    public void onOrderEvent(Message<Map<String, Object>> message) {
        var metadata = CloudEventHeaders.read(message);
        Map<String, Object> payload = message.getPayload();

        if (!ORDER_PAID.equals(metadata.eventType())) {
            // A failed or pending order is not a purchase. Scoring it would teach the
            // profile that a shopper likes things they never actually bought.
            log.debug("Ignoring non-purchase event type={} order={}",
                    metadata.eventType(), metadata.aggregateId());
            return;
        }

        if (alreadyProcessed(metadata.eventId())) {
            log.info("Skipping duplicate affinity update for eventId={} order={}",
                    metadata.eventId(), metadata.aggregateId());
            return;
        }

        try (MDC.MDCCloseable _ = MDC.putCloseable("event_id", metadata.eventId());
             MDC.MDCCloseable __ = MDC.putCloseable("tenant_id", metadata.tenantId())) {

            String customerId = string(payload, "customerId", null);
            List<String> skus = strings(payload, "skus");

            if (customerId == null || skus.isEmpty()) {
                // Nothing to learn from. Marking as processed stops a malformed event
                // being retried to the DLQ forever.
                log.warn("Order event carries no customer or skus; nothing to record "
                        + "eventId={} payloadKeys={}", metadata.eventId(), payload.keySet());
                markProcessed(metadata.eventId());
                return;
            }

            int recorded = recommendationService.recordPurchase(
                    customerId, metadata.tenantId(), skus);

            markProcessed(metadata.eventId());
            log.info("Recorded purchase affinity customer={} order={} skus={} categories={}",
                    customerId, metadata.aggregateId(), skus.size(), recorded);
        }
    }

    /**
     * Best-effort: a Redis outage means a duplicate is possible, which costs some
     * accuracy. Swallowing the check entirely would mean dropping every purchase
     * signal for the duration of the outage, which is far worse - the profile would
     * silently stop learning.
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
            redis.opsForValue().set(DEDUPE_PREFIX + eventId, "1", DEDUPE_WINDOW);
        } catch (RuntimeException e) {
            log.warn("Could not record dedupe marker for {}: {}", eventId, e.toString());
        }
    }

    private String string(Map<String, Object> payload, String field, String fallback) {
        Object value = payload.get(field);
        return value == null ? fallback : value.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return ((Collection<Object>) collection).stream()
                .map(Object::toString)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }
}