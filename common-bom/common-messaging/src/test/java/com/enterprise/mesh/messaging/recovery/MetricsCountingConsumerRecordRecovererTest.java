package com.enterprise.mesh.messaging.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.observability.BusinessMetrics;

/**
 * Exercises the real recoverer, not a copy of it.
 *
 * <p>{@code DeadLetterQueueFilling} in {@code deploy/observability/amp/rules.yaml} evaluated
 * {@code messaging_dead_letter_total}, which nothing published, so the rule could
 * never fire. This is the only emitter, which makes its wiring the thing worth
 * holding in place.
 */
class MetricsCountingConsumerRecordRecovererTest {

    private SimpleMeterRegistry registry;
    private ConsumerRecordRecoverer delegate;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BusinessMetrics> provider(BusinessMetrics metrics) {
        ObjectProvider<BusinessMetrics> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(metrics);
        return provider;
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        delegate = mock(ConsumerRecordRecoverer.class);
    }

    @Test
    void countsThePoisonMessageUnderItsSourceTopic() {
        var recoverer = new MetricsCountingConsumerRecordRecoverer(
                provider(new BusinessMetrics(registry)), delegate);
        Exception failure = new IllegalArgumentException("bad payload");

        recoverer.accept(new ConsumerRecord<>("order.events", 3, 42L, "key", "poison"), failure);

        var counter = registry.find(MetricsCountingConsumerRecordRecoverer.METRIC)
                .tag("topic", "order.events").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        verify(delegate).accept(any(), same(failure));
    }

    @Test
    void usesTheNameTheAlertRuleEvaluates() {
        // DeadLetterQueueFilling reads this exact string. A rename here is a
        // silent break, so it is pinned next to the constant the rule depends on.
        assertThat(MetricsCountingConsumerRecordRecoverer.METRIC)
                .isEqualTo("messaging_dead_letter_total");
    }

    @Test
    void stillDelegatesWhenMetricsAreUnavailable() {
        // A worker may run without the observability auto-configuration. Losing a
        // metric must never cost the operator the message.
        Exception failure = new IllegalArgumentException("bad payload");

        new MetricsCountingConsumerRecordRecoverer(provider(null), delegate)
                .accept(new ConsumerRecord<>("order.events", 0, 1L, "k", "v"), failure);

        verify(delegate).accept(any(), same(failure));
        assertThat(registry.find(MetricsCountingConsumerRecordRecoverer.METRIC).counter())
                .as("no series is better than a wrong one")
                .isNull();
    }

    @Test
    void taggedTopicAgreesWithTheDotDlqDestination() {
        // The alert tells operators to inspect ".dlq"; the tag names the SOURCE
        // topic. Both derive from the same record, so they cannot disagree.
        assertThat(MessagingTopics.deadLetter("order.events")).isEqualTo("order.events.dlq");

        new MetricsCountingConsumerRecordRecoverer(provider(new BusinessMetrics(registry)), delegate)
                .accept(new ConsumerRecord<>("order.events", 0, 1L, "k", "v"), new RuntimeException());

        assertThat(registry.find(MetricsCountingConsumerRecordRecoverer.METRIC)
                .tag("topic", "order.events").counter().count()).isEqualTo(1.0);
    }
}
