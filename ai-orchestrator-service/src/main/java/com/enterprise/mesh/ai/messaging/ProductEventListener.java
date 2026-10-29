package com.enterprise.mesh.ai.messaging;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.enterprise.mesh.ai.semantic.SemanticIndexService;
import com.enterprise.mesh.ai.semantic.SemanticSearchUnavailableException;
import com.enterprise.mesh.messaging.MessagingTopics;
import com.enterprise.mesh.messaging.event.CloudEventHeaders;

/**
 * Keeps the semantic-search vector index in step with the catalogue.
 *
 * <p>The only consumer of {@code product.events}, and the reason that topic exists.
 *
 * <p><b>No Redis dedupe, unlike {@link OrderEventListener}.</b> That listener needs one because
 * a duplicate would double-count a purchase weight. Here the operation is naturally idempotent -
 * {@code SemanticIndexService} fingerprints the text it embeds and skips an unchanged product,
 * and {@code remove} tolerates an absent row - so a redelivered event costs one cheap hash
 * comparison instead of a round trip to Redis. Adding dedupe here would be infrastructure with
 * nothing to protect.
 *
 * <p><b>A missing model must not stall the consumer group.</b> Throwing on an unavailable
 * embedder would send every product change to the DLQ for as long as the model is absent, which
 * is the opposite of what is wanted: the catalogue change has already committed, and the index
 * is rebuilt by reindex when the model comes back. So the event is acknowledged and logged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventListener {

    private static final String PRODUCT_DEACTIVATED = "product.deactivated";

    private final SemanticIndexService indexService;

    @KafkaListener(
            topics = MessagingTopics.PRODUCT_EVENTS,
            groupId = "${mesh.ai.semantic.consumer-group:semantic-indexer}")
    public void onProductEvent(Message<Map<String, Object>> message) {
        var metadata = CloudEventHeaders.read(message);
        Map<String, Object> payload = message.getPayload();

        String tenantId = metadata.tenantId();
        String sku = string(payload, "sku");
        if (tenantId == null || tenantId.isBlank() || sku == null || sku.isBlank()) {
            log.warn("Product event carries no tenant or sku; nothing to index eventId={} "
                    + "payloadKeys={}", metadata.eventId(), payload.keySet());
            return;
        }

        try (org.slf4j.MDC.MDCCloseable _ = org.slf4j.MDC.putCloseable("event_id", metadata.eventId());
             org.slf4j.MDC.MDCCloseable _ = org.slf4j.MDC.putCloseable("tenant_id", tenantId)) {

            if (PRODUCT_DEACTIVATED.equals(metadata.eventType())) {
                boolean removed = indexService.remove(tenantId, sku);
                log.info("Deactivated product removed from index sku={} tenant={} existed={}",
                        sku, tenantId, removed);
                return;
            }

            boolean written = indexService.index(tenantId, sku, searchText(payload));
            log.info("Product indexed sku={} tenant={} rewritten={}", sku, tenantId, written);

        } catch (SemanticSearchUnavailableException e) {
            // Acknowledged deliberately. See the class javadoc: rethrowing would DLQ every
            // catalogue change until the model is mounted, and a reindex recovers the index
            // regardless of how many events were lost.
            log.warn("Semantic search unavailable; product event not applied sku={} tenant={}. "
                    + "Run a reindex once the model is present. ({})", sku, tenantId, e.getMessage());
        }
    }

    /**
     * The same field order {@code SemanticIndexService.IndexableProduct.searchText()} uses.
     *
     * <p>Duplicated rather than shared across services on purpose - it is a Kafka payload
     * contract, and coupling the consumer to the producer's source record would make a payload
     * change a compile-time coupling across a service boundary that only exists at runtime.
     * {@code ProductEventIndexingTest} pins the order on both sides.
     */
    private String searchText(Map<String, Object> payload) {
        StringBuilder builder = new StringBuilder();
        append(builder, string(payload, "name"));
        append(builder, string(payload, "brand"));
        append(builder, string(payload, "description"));
        return builder.toString();
    }

    private void append(StringBuilder builder, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(part.strip());
    }

    private String string(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? null : value.toString();
    }
}