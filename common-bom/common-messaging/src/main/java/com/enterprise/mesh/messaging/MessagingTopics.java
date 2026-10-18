package com.enterprise.mesh.messaging;

/**
 * Canonical Kafka topic names for the mesh.
 *
 * <p>Naming convention: {@code <domain>.events} in lower dotted form. Every topic is partitioned
 * and keyed by aggregate id, so ordering per aggregate is guaranteed while throughput scales
 * horizontally.
 *
 * <p>Dead-letter convention: a failing topic {@code X} uses {@code X.dlq}.
 *
 * <p><b>A topic is declared here only once it has a producer and a consumer.</b> This class used
 * to declare seven topics, six of which existed only as constants and were nonetheless
 * auto-created on the broker by {@code TopicBootstrap} - so a reader had no way to tell a live
 * flow from an aspiration, and the broker held real topics for flows that did not exist.
 *
 * <p>Several were not merely unused but would have been wrong to build. {@code inventory.events}
 * and {@code payment.events} were described as reservation and authorisation <em>requests and
 * results</em>, i.e. synchronous request/reply over Kafka - but checkout is a compensatable saga
 * driven by direct HTTP calls in {@code CheckoutSagaOrchestrator}, and it cannot compensate
 * against a fire-and-forget message. {@code order.notifications} duplicated {@code order.events}
 * for the same two consumers.
 *
 * <p>Adding one means adding a producer and a consumer in the same change.
 */
public final class MessagingTopics {

    private MessagingTopics() {
    }

    /** Order lifecycle events; the backbone of the checkout flow. */
    public static final String ORDER_EVENTS = "order.events";

    /**
     * Product create/update/deactivate. Consumed by {@code ai-orchestrator-service} to keep the
     * semantic-search vector index in step with the catalogue.
     */
    public static final String PRODUCT_EVENTS = "product.events";

    public static String deadLetter(String topic) {
        return topic + ".dlq";
    }
}