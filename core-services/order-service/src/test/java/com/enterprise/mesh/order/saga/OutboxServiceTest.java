package com.enterprise.mesh.order.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import com.enterprise.mesh.messaging.event.EventMetadata;
import com.enterprise.mesh.order.persistence.OutboxEvent;
import com.enterprise.mesh.order.persistence.OutboxEventRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Locks the shape of the {@code outbox_events.payload} column.
 *
 * <p>This column is a contract between two systems that no test in this repository
 * connects: this service writes it, and Debezium's outbox event router publishes
 * it verbatim as the Kafka record value. Consumers across the mesh then read that
 * value as the event body.
 *
 * <p>The failure this guards against is silent by construction. If the payload
 * were wrapped in a metadata envelope again, every consumer would still boot and
 * still receive a message - it would simply not find its fields. The shipping
 * listener is the clearest example: {@code customerId} would come back null, it
 * logs "cannot fulfil", and returns. No shipment is created and nothing anywhere
 * reports an error.
 *
 * <p>There is no test that can catch this by starting a context, because the
 * mismatch only appears once a real Debezium connector is running against a real
 * database. Asserting the stored shape here is the cheapest place to hold it.
 */
class OutboxServiceTest {

    private OutboxEventRepository repository;
    private OutboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        service = new OutboxService(repository, new ObjectMapper());
    }

    @Test
    @DisplayName("the payload column holds the bare event, not an envelope around it")
    void payloadIsNotWrappedInAnEnvelope() {
        record OrderCreatedEvent(String orderId, String customerId, String status) {
        }
        OrderCreatedEvent event = new OrderCreatedEvent("order-1", "cust-9", "PAID");

        service.record("order.events", event,
                EventMetadata.of("order.paid", "order", "order-1", "acme", "corr-7"));

        OutboxEvent stored = capture();
        assertThat(stored.getPayload())
                .as("Debezium publishes this column as the Kafka record value, and consumers read "
                        + "that value as the event itself. Wrapping it would nest the real event "
                        + "inside an envelope and every consumer would find its fields missing.")
                .isEqualTo("{\"orderId\":\"order-1\",\"customerId\":\"cust-9\",\"status\":\"PAID\"}");
    }

    @Test
    @DisplayName("routing and metadata live in columns, not in the payload")
    void metadataIsCarriedByColumns() {
        service.record("order.events", java.util.Map.of("orderId", "order-1"),
                EventMetadata.of("order.paid", "order", "order-1", "acme", "corr-7"));

        OutboxEvent stored = capture();

        // Each of these is read by the Debezium connector to build headers and
        // the record key. If one were only inside the payload it would be lost.
        assertThat(stored.getTopic()).isEqualTo("order.events");
        assertThat(stored.getEventType()).isEqualTo("order.paid");
        assertThat(stored.getAggregateType()).isEqualTo("order");
        assertThat(stored.getAggregateId()).isEqualTo("order-1");
        assertThat(stored.getTenantId()).isEqualTo("acme");
        assertThat(stored.getCorrelationId()).isEqualTo("corr-7");
        assertThat(stored.getSchemaVersion()).isEqualTo(1);
        assertThat(stored.getEventId()).as("consumer-side idempotency key").isNotBlank();
        assertThat(stored.getCreatedAt()).as("order column Debezium maps as the record timestamp")
                .isNotNull();
    }

    @Test
    @DisplayName("a null correlation id is stored as null, not defaulted")
    void toleratesNullCorrelationId() {
        service.record("order.events", java.util.Map.of("orderId", "order-1"),
                EventMetadata.of("order.paid", "order", "order-1", "acme", null));

        assertThat(capture().getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("an oversized payload is rejected rather than written")
    void rejectsOversizedPayload() {
        // MAX_PAYLOAD_BYTES is 256 KiB; 300 KiB of payload must not reach the table.
        String huge = "x".repeat(300 * 1024);

        assertThatThrownBy(() -> service.record("order.events",
                java.util.Map.of("blob", huge),
                EventMetadata.of("order.paid", "order", "order-1", "acme", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    private OutboxEvent capture() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        return captor.getValue();
    }
}