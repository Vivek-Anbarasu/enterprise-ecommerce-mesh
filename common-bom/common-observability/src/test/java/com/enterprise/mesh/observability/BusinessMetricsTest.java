package com.enterprise.mesh.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

/**
 * Pins metric NAMES, because that is the only part of an alert rule and its
 * emitter that can silently drift apart.
 *
 * <p>{@code deploy/observability/amp/rules.yaml} evaluates metric
 * names as strings. Two rules in that file referenced metrics no service
 * published - {@code inventory_concurrent_update_total} and
 * {@code messaging_dead_letter_total} - so both evaluated to empty forever and
 * nobody found out. The failure is invisible in a build, because both sides
 * compile; it only shows up as a rule that can never fire.
 *
 * <p>Publishing through one shared helper keeps the name a single decision, and
 * these tests keep the emitted spelling pinned to what the rules read.
 */
class BusinessMetricsTest {

    /**
     * Names referenced by {@code DeadLetterQueueFilling} and
     * {@code InventoryLockContention}. If an emitter is renamed, this fails at
     * build time rather than the alert failing silently in production.
     */
    private static final List<String> RULES_DEPENDENT_ON_EMITTER = List.of(
            "inventory_concurrent_update_total",
            "messaging_dead_letter_total");

    @Test
    void incrementsPublishTheExactNameTheAlertRuleEvaluates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        for (String name : RULES_DEPENDENT_ON_EMITTER) {
            metrics.increment(name, Map.of("topic", "order.events"));
            assertThat(registry.find(name).counter())
                    .as("rule reads %s; an emitter under any other spelling never fires", name)
                    .isNotNull();
        }
    }

    @Test
    void carriesTagsThroughToTheSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.increment("inventory_concurrent_update_total", Map.of("action", "RESERVE"));

        Counter counter = registry.find("inventory_concurrent_update_total").tag("action", "RESERVE").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void reusesOneMeterPerNameAndTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.increment("messaging_dead_letter_total", Map.of("topic", "order.events"));
        metrics.increment("messaging_dead_letter_total", Map.of("topic", "order.events"));
        metrics.increment("messaging_dead_letter_total", Map.of("topic", "shipping.events"));

        assertThat(registry.find("messaging_dead_letter_total").counters())
                .as("one series per distinct tag set, not one per call")
                .hasSize(2);
        assertThat(registry.find("messaging_dead_letter_total").tag("topic", "order.events").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void businessEventCounterIsUnchanged() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.recordOutcome("orders", "paid");
        metrics.recordOutcome("orders", "paid");

        Counter counter = registry.find("mesh_business_events_total")
                .tag("domain", "orders").tag("outcome", "paid").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }
}
