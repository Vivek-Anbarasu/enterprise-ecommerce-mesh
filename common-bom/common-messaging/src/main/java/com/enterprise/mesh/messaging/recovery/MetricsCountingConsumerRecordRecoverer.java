package com.enterprise.mesh.messaging.recovery;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import com.enterprise.mesh.observability.BusinessMetrics;

/**
 * Counts records diverted to a dead-letter topic, then hands them to the real
 * recoverer.
 *
 * <p>Exists as a named type rather than a lambda inside the auto-configuration so
 * that it can be tested directly. An inline wrapper is easy to delete by accident
 * and impossible to assert on, and the failure mode is silent: the
 * {@code DeadLetterQueueFilling} alert would go on evaluating a metric nothing
 * publishes, and a partition wedged on poison messages would look healthy.
 *
 * <p>{@link BusinessMetrics} is optional. A worker may run without the
 * observability auto-configuration, and losing a metric must never cost an
 * operator the message — so the delegate is invoked unconditionally, before any
 * decision about whether the count succeeded.
 */
public class MetricsCountingConsumerRecordRecoverer implements ConsumerRecordRecoverer {

    /** Metric name read by the {@code DeadLetterQueueFilling} alert rule. */
    public static final String METRIC = "messaging_dead_letter_total";

    private final ObjectProvider<BusinessMetrics> businessMetrics;
    private final ConsumerRecordRecoverer delegate;

    public MetricsCountingConsumerRecordRecoverer(ObjectProvider<BusinessMetrics> businessMetrics,
                                                  ConsumerRecordRecoverer delegate) {
        this.businessMetrics = businessMetrics;
        this.delegate = delegate;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        BusinessMetrics metrics = businessMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.increment(METRIC, Map.of("topic", record.topic()));
        }
        delegate.accept(record, exception);
    }
}
