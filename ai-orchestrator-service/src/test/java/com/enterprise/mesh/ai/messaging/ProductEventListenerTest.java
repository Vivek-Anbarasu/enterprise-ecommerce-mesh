package com.enterprise.mesh.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import com.enterprise.mesh.ai.semantic.SemanticIndexService;
import com.enterprise.mesh.ai.semantic.SemanticSearchUnavailableException;
import com.enterprise.mesh.messaging.event.CloudEventHeaders;

/**
 * The product.events consumer.
 *
 * <p>Two properties matter more than the happy path and are pinned here: that an unavailable
 * embedding model does <em>not</em> stall the consumer group, and that the field order used to
 * build the embedded text matches what the producer wrote.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductEventListenerTest {

    @Mock
    private SemanticIndexService indexService;

    private ProductEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ProductEventListener(indexService);
    }

    private Message<Map<String, Object>> event(String type, Map<String, Object> payload) {
        Map<String, Object> headers = new LinkedHashMap<>();
        // CloudEvents binary content mode: type and tenant ride in ce_* headers, not the body.
        // Body keys here would produce a null tenant and a silently no-op listener.
        headers.put(CloudEventHeaders.EVENT_ID, "evt-1");
        headers.put(CloudEventHeaders.EVENT_TYPE, type);
        headers.put(CloudEventHeaders.AGGREGATE_TYPE, "product");
        headers.put(CloudEventHeaders.AGGREGATE_ID, "acme:BOOTS-1");
        headers.put(CloudEventHeaders.TENANT_ID, "acme");
        return MessageBuilder.withPayload(payload).copyHeaders(headers).build();
    }

    private Map<String, Object> product() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sku", "BOOTS-1");
        payload.put("tenantId", "acme");
        payload.put("name", "Waterproof Boots");
        payload.put("brand", "Acme");
        payload.put("description", "Tall hiking boots");
        return payload;
    }

    @Test
    @DisplayName("a create event embeds the product")
    void indexesOnCreate() {
        listener.onProductEvent(event("product.created", product()));

        verify(indexService).index(eq("acme"), eq("BOOTS-1"), eq("Waterproof Boots Acme Tall hiking boots"));
    }

    @Test
    @DisplayName("an update event re-embeds the product")
    void indexesOnUpdate() {
        listener.onProductEvent(event("product.updated", product()));

        verify(indexService).index(eq("acme"), eq("BOOTS-1"), anyString());
    }

    @Test
    @DisplayName("a deactivation removes the vector instead of indexing it")
    void removesOnDeactivate() {
        // A withdrawn product that kept its vector goes on answering queries, and a shopper is
        // shown something the catalogue no longer sells.
        listener.onProductEvent(event("product.deactivated", product()));

        verify(indexService).remove("acme", "BOOTS-1");
        verify(indexService, org.mockito.Mockito.never()).index(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an unavailable model is acknowledged rather than dead-lettered")
    void unavailableModelDoesNotStallTheConsumerGroup() {
        // Rethrowing would send every catalogue change to the DLQ for as long as the model is
        // absent. The change has already committed upstream, and a reindex recovers regardless
        // of how many events were lost, so acknowledging is the correct outcome.
        given(indexService.index(anyString(), anyString(), anyString()))
                .willThrow(new SemanticSearchUnavailableException("model not loaded"));

        listener.onProductEvent(event("product.created", product()));
        // Reaching here without propagating is the assertion.
        verify(indexService).index(eq("acme"), eq("BOOTS-1"), anyString());
    }

    @Test
    @DisplayName("an event with no sku is dropped without touching the index")
    void missingSkuIsDropped() {
        Map<String, Object> payload = product();
        payload.remove("sku");

        listener.onProductEvent(event("product.created", payload));

        verifyNoInteractions(indexService);
    }

    @Test
    @DisplayName("missing optional fields are omitted rather than embedded as blanks")
    void missingTextFieldsAreOmitted() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sku", "COAT-9");
        payload.put("tenantId", "acme");
        payload.put("description", "Warm wool coat");
        payload.put("brand", "   ");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        listener.onProductEvent(event("product.created", payload));

        verify(indexService).index(eq("acme"), eq("COAT-9"), text.capture());
        assertThat(text.getValue())
                .as("A blank brand must not become a double space in the embedded text")
                .isEqualTo("Warm wool coat");
    }
}