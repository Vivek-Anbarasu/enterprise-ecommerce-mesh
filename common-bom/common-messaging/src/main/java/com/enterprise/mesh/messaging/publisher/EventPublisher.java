package com.enterprise.mesh.messaging.publisher;

import java.util.concurrent.CompletableFuture;

import com.enterprise.mesh.messaging.event.EventMetadata;

/** Publishes domain events; abstracted so services can be tested without Kafka. */
public interface EventPublisher {

    /**
     * Publishes {@code payload} with {@code metadata} to {@code topic}.
     *
     * @return a future completing once the broker acknowledges the record
     */
    CompletableFuture<Void> publish(String topic, Object payload, EventMetadata metadata);
}
