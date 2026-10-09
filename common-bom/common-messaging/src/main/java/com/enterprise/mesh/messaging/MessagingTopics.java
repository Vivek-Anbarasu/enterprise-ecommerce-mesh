package com.enterprise.mesh.messaging;

/**
 * Canonical Kafka topic names for the mesh.
 *
 * <p>Naming convention: {@code <domain>.<entity>.<action>} in lower kebab/dot
 * form. Every topic is partitioned and keyed by aggregate id so that ordering
 * per aggregate is guaranteed while throughput scales horizontally.
 *
 * <p>Dead-letter convention: a failing topic {@code X} uses {@code X.dlq}.
 */
public final class MessagingTopics {

    private MessagingTopics() {
    }

    /** Order lifecycle events; the backbone of the checkout flow. */
    public static final String ORDER_EVENTS = "order.events";

    /** Inventory reservation/release requests and results. */
    public static final String INVENTORY_EVENTS = "inventory.events";

    /** Payment authorisation / capture / refund requests and results. */
    public static final String PAYMENT_EVENTS = "payment.events";

    /** Order lifecycle fan-out used by notification and shipping consumers. */
    public static final String ORDER_NOTIFICATIONS = "order.notifications";

    /** Shipment tracking updates. */
    public static final String SHIPPING_EVENTS = "shipping.events";

    /** Review/rating writes. */
    public static final String REVIEW_EVENTS = "review.events";

    /** Append-only audit trail; consumers must be idempotent and lossless. */
    public static final String AUDIT_TRAIL = "audit.trail";

    public static String deadLetter(String topic) {
        return topic + ".dlq";
    }
}
