package com.enterprise.mesh.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.enterprise.mesh.ai.service.RecommendationService;
import com.enterprise.mesh.messaging.event.CloudEventHeaders;
import com.enterprise.mesh.messaging.event.EventMetadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;

/**
 * The purchase feed, which is the half of interaction recording that must not be
 * client-asserted.
 *
 * <p>Three properties matter and each is asserted separately: a purchase is recorded,
 * a duplicate is not, and a non-purchase event never scores a profile. The last is easy
 * to get wrong and quietly poisons the model - a failed checkout would otherwise teach a
 * shopper a preference they never had.
 */
class OrderEventListenerTest {

    private RecommendationService recommendationService;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private OrderEventListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked") // Mockito cannot express the type parameters of ValueOperations
    void setUp() {
        recommendationService = org.mockito.Mockito.mock(RecommendationService.class);
        redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.hasKey(anyString())).thenReturn(false);
        when(redis.opsForValue()).thenReturn(valueOps);
        listener = new OrderEventListener(recommendationService, redis);
    }

    /**
     * CloudEventHeaders.toMessage returns {@code Message<Object>}; the listener takes a
     * {@code Message<Map<String, Object>>}. The cast is confined here rather than
     * leaking into every assertion.
     */
    @SuppressWarnings("unchecked")
    private static Message<Map<String, Object>> typed(Message<Object> message) {
        return (Message<Map<String, Object>>) (Message<?>) message;
    }

    private Message<Object> event(String type, String eventId, String customerId, List<String> skus) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", "order-1");
        if (customerId != null) {
            payload.put("customerId", customerId);
        }
        payload.put("skus", skus);

        EventMetadata metadata = new EventMetadata(eventId, type, "Order", "order-1",
                "acme", 1, Instant.now(), "corr-1", null);
        return CloudEventHeaders.toMessage(payload, metadata);
    }

    @Test
    @DisplayName("a paid order records a purchase for the customer and tenant")
    void purchaseIsRecorded() {
        listener.onOrderEvent(typed(event("order.paid", "evt-1", "cust-1",
                List.of("SKU-1", "SKU-2"))));

        verify(recommendationService).recordPurchase("cust-1", "acme", List.of("SKU-1", "SKU-2"));
    }

    @Test
    @DisplayName("the tenant comes from the CloudEvent header, not the payload")
    void tenantComesFromCloudEventHeader() {
        listener.onOrderEvent(typed(event("order.paid", "evt-1", "cust-1", List.of("SKU-1"))));

        verify(recommendationService).recordPurchase("cust-1", "acme", List.of("SKU-1"));
    }

    @Test
    @DisplayName("a redelivered event is skipped rather than double-weighting the profile")
    void duplicateIsSkipped() {
        // The reason this listener needs a dedupe marker at all. Purchases decay by only
        // 15% per subsequent interaction, so a double-counted purchase biases the profile
        // across the whole 90-day retention window.
        when(redis.hasKey("ai:processed:evt-1")).thenReturn(true);

        listener.onOrderEvent(typed(event("order.paid", "evt-1", "cust-1", List.of("SKU-1"))));

        verifyNoInteractions(recommendationService);
    }

    @Test
    @DisplayName("the processed marker is written after a successful record")
    void markerWrittenAfterSuccess() {
        listener.onOrderEvent(typed(event("order.paid", "evt-1", "cust-1", List.of("SKU-1"))));

        verify(valueOps).set(eq("ai:processed:evt-1"), eq("1"),
                ArgumentMatchers.<Duration>any());
    }

    @Test
    @DisplayName("order.failed does not score a purchase")
    void failedOrderIsIgnored() {
        // The quiet corruption this prevents: a shopper whose payment declined would
        // otherwise build a preference for everything in the basket they never bought.
        listener.onOrderEvent(typed(event("order.failed", "evt-9", "cust-1", List.of("SKU-1"))));

        verifyNoInteractions(recommendationService);
    }

    @Test
    @DisplayName("an event with no skus is marked processed and not retried forever")
    void malformedEventIsNotRetried() {
        listener.onOrderEvent(typed(event("order.paid", "evt-bad", null, List.of())));

        verify(recommendationService, never()).recordPurchase(anyString(), anyString(), any());
        // Marked, so a malformed producer cannot keep this partition hot by redelivering.
        verify(valueOps).set(eq("ai:processed:evt-bad"), eq("1"), ArgumentMatchers.<Duration>any());
    }

    @Test
    @DisplayName("a Redis outage degrades to recording the purchase, not dropping it")
    void dedupeOutageStillRecords() {
        // Duplicates are recoverable by expiring the profile; a lost purchase signal is
        // not. The asymmetry is deliberate.
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        listener.onOrderEvent(typed(event("order.paid", "evt-1", "cust-1", List.of("SKU-1"))));

        verify(recommendationService).recordPurchase("cust-1", "acme", List.of("SKU-1"));
    }

    @Test
    @DisplayName("the listener is bound to the mesh consumer group, not the yml one")
    void groupIdComesFromTheAnnotation() throws NoSuchMethodException {
        KafkaListener annotation = OrderEventListener.class
                .getMethod("onOrderEvent", Message.class)
                .getAnnotation(KafkaListener.class);

        // spring.kafka.consumer.group-id would be silently overridden by this, so the
        // effective group is readable here and nowhere else. Asserting it stops the two
        // drifting apart again.
        assertThat(annotation.groupId()).isEqualTo("${mesh.ai.consumer-group:ai-orchestrator}");
        assertThat(annotation.topics()).containsExactly("order.events");
    }

    @Test
    @DisplayName("an event id is namespaced per consumer in the dedupe key")
    void dedupeKeyIsNamespaced() {
        listener.onOrderEvent(typed(event("order.paid", UUID.randomUUID().toString(),
                "cust-1", List.of("SKU-1"))));

        verify(redis).hasKey(startsWithAiProcessed());
        verify(valueOps).set(startsWithAiProcessed(), eq("1"), ArgumentMatchers.<Duration>any());
    }

    private static String startsWithAiProcessed() {
        return org.mockito.ArgumentMatchers.startsWith("ai:processed:");
    }
}