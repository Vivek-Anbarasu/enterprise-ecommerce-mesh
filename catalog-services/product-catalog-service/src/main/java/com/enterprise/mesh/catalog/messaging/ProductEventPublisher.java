package com.enterprise.mesh.catalog.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.enterprise.mesh.catalog.domain.Product;
import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.EventMetadata;
import com.enterprise.mesh.messaging.publisher.EventPublisher;

/**
 * Forwards catalogue changes onto {@code product.events}.
 *
 * <p><b>{@code AFTER_COMMIT}, not inside the transaction.</b> Publishing inline would be the
 * dual-write problem in miniature: a Kafka record could reach the consumer while the Mongo
 * transaction later rolls back, describing a product change that never happened, and the indexer
 * would embed a product the catalogue does not contain. Deferring to commit removes that
 * ordering entirely - if the transaction rolls back, no event exists.
 *
 * <p><b>Not a transactional outbox, unlike {@code order-service}.</b> A deliberate asymmetry
 * rather than an oversight, and it turns on what is lost when an event goes missing. A lost
 * {@code order.paid} loses a customer purchase and cannot be reconstructed; a lost
 * {@code product.updated} leaves one stale vector in a derived index, which a reindex rebuilds
 * from the catalogue - still the authority. Paying for outbox infrastructure, a table and a
 * relay and a dead-letter story, to protect rebuildable state would be the more complex wrong
 * answer. If product writes become high-volume and the index grows large enough that one stale
 * vector matters, extract the outbox into {@code common-messaging} and use it here.
 *
 * <p><b>A publish failure is logged, not propagated.</b> The write has committed and the
 * merchant's request has already succeeded; failing the response now would report a catalogue
 * write that did not fail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventPublisher {

    /** Event types, held here so producer and consumer cannot drift on a typo. */
    public static final String CREATED = "product.created";
    public static final String UPDATED = "product.updated";
    public static final String DEACTIVATED = "product.deactivated";

    private static final String AGGREGATE_TYPE = "product";

    private final EventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductChanged(ProductChangeNotice notice) {
        ProductChangedEvent product = notice.product();

        // aggregateId is tenant-qualified so two tenants stocking the same SKU are distinct
        // aggregates. Keyed by it, so every change to one product lands on one partition and is
        // consumed in order; two rapid edits must not be embedded out of sequence.
        String aggregateId = product.tenantId() + ":" + product.sku();
        EventMetadata metadata = EventMetadata.of(
                notice.eventType(), AGGREGATE_TYPE, aggregateId, product.tenantId(), null);

        eventPublisher.publish(MessagingTopics.PRODUCT_EVENTS, product, metadata)
                .exceptionally(failure -> {
                    log.error("Failed to publish {} for sku={} tenant={}. The semantic index stays "
                                    + "stale for this product until the next reindex: {}",
                            notice.eventType(), product.sku(), product.tenantId(),
                            failure.toString());
                    return null;
                });
    }

    /** The payload for a saved product. Active state travels with it so the consumer knows. */
    public static ProductChangedEvent of(Product product) {
        return new ProductChangedEvent(
                product.getSku(),
                product.getTenantId(),
                product.getName(),
                product.getBrand(),
                product.getDescription(),
                product.isActive());
    }
}