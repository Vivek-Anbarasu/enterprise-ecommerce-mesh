package com.enterprise.mesh.observability;
import lombok.RequiredArgsConstructor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Thin, allocation-free wrapper over Micrometer for the business events that
 * matter commercially (orders placed, payments captured, stock rejected).
 *
 * <p>Counter and timer instances are cached because Micrometer resolves and
 * caches a meter per unique name+tags combination anyway; doing the lookup once
 * here keeps request paths off the registry's internal maps.
 */
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    /** Increments {@code mesh_business_events_total} for the given domain and outcome. */
    public void recordOutcome(String domain, String outcome) {
        counters.computeIfAbsent("mesh_business_events_total",
                name -> Counter.builder(name)
                        .description("Business events by domain and outcome")
                        .tag("domain", domain)
                        .tag("outcome", outcome)
                        .register(registry))
                .increment();
    }

    /**
     * Adds {@code amount} to a running in-process gauge, e.g. live carts.
     *
     * <p>Stores the very {@link AtomicLong} instance handed to the registry so
     * that increments are actually reflected in the scraped value. Note that
     * these gauges are per-pod; use a Prometheus aggregation (sum/avg) when the
     * value must be cluster-wide.
     */
    public void addToGauge(String metric, String tagKey, String tagValue, long amount) {
        AtomicLong holder = gauges.computeIfAbsent(metric + "|" + tagKey + "|" + tagValue, key ->
                registry.gauge(metric, Tags.of(tagKey, tagValue), new AtomicLong(0)));
        holder.addAndGet(amount);
    }

    /** Reads the current value of a gauge registered via {@link #addToGauge}. */
    public long gaugeValue(String metric, String tagKey, String tagValue) {
        AtomicLong holder = gauges.get(metric + "|" + tagKey + "|" + tagValue);
        return holder == null ? 0L : holder.get();
    }

    /** Times a unit of business work, recording failures separately. */
    public <T> T recordTiming(String domain, String operation, TimedSupplier<T> supplier) {
        Timer timer = Timer.builder("mesh_business_operation_seconds")
                .description("Business operation latency")
                .tag("domain", domain)
                .tag("operation", operation)
                .publishPercentileHistogram()
                .register(registry);
        long start = System.nanoTime();
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            recordOutcome(domain, operation + "_error");
            throw e;
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @FunctionalInterface
    public interface TimedSupplier<T> {
        T get();
    }
}