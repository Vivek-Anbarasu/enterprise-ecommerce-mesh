package com.enterprise.mesh.catalog.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.enterprise.mesh.catalog.domain.Product;
import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.EventMetadata;
import com.enterprise.mesh.messaging.publisher.EventPublisher;

/**
 * The product.events producer.
 *
 * <p>Two things are asserted: that the event lands on the right topic keyed for per-product
 * ordering, and that a broker failure is logged rather than propagated. The second matters
 * because this listener runs {@code AFTER_COMMIT} - the write has already succeeded, so
 * rethrowing would tell a merchant their catalogue write failed when it did not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductEventPublisherTest {

    @Mock
    private EventPublisher eventPublisher;

    private ProductEventPublisher publisher;

    @Test
    void publishesToProductEvents() {
        publisher = new ProductEventPublisher(eventPublisher);
        given(eventPublisher.publish(any(), any(), any())).willReturn(CompletableFuture.completedFuture(null));

        publisher.onProductChanged(new ProductChangeNotice(
                ProductEventPublisher.CREATED, sampleProduct()));

        ArgumentCaptor<EventMetadata> metadata = ArgumentCaptor.forClass(EventMetadata.class);
        verify(eventPublisher).publish(eq(MessagingTopics.PRODUCT_EVENTS), any(), metadata.capture());

        assertThat(metadata.getValue().eventType()).isEqualTo("product.created");
        assertThat(metadata.getValue().aggregateType()).isEqualTo("product");
        // Tenant-qualified: two tenants stocking the same SKU are distinct aggregates.
        assertThat(metadata.getValue().aggregateId()).isEqualTo("acme:BOOTS-1");
        assertThat(metadata.getValue().tenantId()).isEqualTo("acme");
        assertThat(metadata.getValue().eventId()).isNotBlank();
    }

    @Test
    @DisplayName("the payload carries the embeddable text and the active flag")
    void carriesTheEmbeddableText() {
        publisher = new ProductEventPublisher(eventPublisher);
        given(eventPublisher.publish(any(), any(), any())).willReturn(CompletableFuture.completedFuture(null));

        publisher.onProductChanged(new ProductChangeNotice(
                ProductEventPublisher.DEACTIVATED, sampleProduct()));

        ArgumentCaptor<ProductChangedEvent> payload = ArgumentCaptor.forClass(ProductChangedEvent.class);
        verify(eventPublisher).publish(eq(MessagingTopics.PRODUCT_EVENTS), payload.capture(), any());

        assertThat(payload.getValue().sku()).isEqualTo("BOOTS-1");
        assertThat(payload.getValue().name()).isEqualTo("Waterproof Boots");
        assertThat(payload.getValue().brand()).isEqualTo("Acme");
        assertThat(payload.getValue().active()).isFalse();
    }

    @Test
    @DisplayName("a broker failure is swallowed, not propagated")
    void brokerFailureDoesNotPropagate() {
        // The write has committed and the merchant's request has already succeeded. Failing the
        // response now would report a catalogue write that did not fail, and the merchant would
        // retry a create that already happened.
        publisher = new ProductEventPublisher(eventPublisher);
        given(eventPublisher.publish(any(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.onProductChanged(new ProductChangeNotice(
                ProductEventPublisher.UPDATED, sampleProduct()));

        // No exception escaped; the stale-index remedy is a reindex.
        verify(eventPublisher).publish(eq(MessagingTopics.PRODUCT_EVENTS), any(), any());
    }

    @Test
    @DisplayName("a notice with no type or payload is rejected at construction")
    void incompleteNoticeIsRejected() {
        ProductChangedEvent product = sampleProduct();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ProductChangeNotice("  ", product))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ProductChangeNotice(ProductEventPublisher.CREATED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProductChangedEvent sampleProduct() {
        Product product = new Product();
        product.setSku("BOOTS-1");
        product.setTenantId("acme");
        product.setName("Waterproof Boots");
        product.setBrand("Acme");
        product.setDescription("Tall hiking boots");
        product.setActive(false);
        return ProductEventPublisher.of(product);
    }
}