package com.enterprise.mesh.messaging.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Kafka header names and helpers for CloudEvents-style binary content mode.
 */
public final class CloudEventHeaders {

    public static final String EVENT_ID = "ce_eventid";
    public static final String EVENT_TYPE = "ce_type";
    public static final String AGGREGATE_TYPE = "ce_aggregatetype";
    public static final String AGGREGATE_ID = "ce_aggregateid";
    public static final String TENANT_ID = "ce_tenantid";
    public static final String SCHEMA_VERSION = "ce_schemaversion";
    public static final String OCCURRED_AT = "ce_occurredat";
    public static final String CORRELATION_ID = "ce_correlationid";
    public static final String CAUSATION_ID = "ce_causationid";

    private CloudEventHeaders() {
    }

    /** Builds a message whose payload is {@code payload} and whose headers carry {@code metadata}. */
    public static Message<Object> toMessage(Object payload, EventMetadata metadata) {
        MessageBuilder<Object> builder = MessageBuilder.withPayload(payload)
                .setHeader(EVENT_ID, metadata.eventId())
                .setHeader(EVENT_TYPE, metadata.eventType())
                .setHeader(AGGREGATE_TYPE, metadata.aggregateType())
                .setHeader(AGGREGATE_ID, metadata.aggregateId())
                .setHeader(TENANT_ID, metadata.tenantId())
                .setHeader(SCHEMA_VERSION, metadata.schemaVersion())
                .setHeader(OCCURRED_AT, metadata.occurredAt().toString())
                .setHeader(CORRELATION_ID, metadata.correlationId());

        if (metadata.causationId() != null) {
            builder.setHeader(CAUSATION_ID, metadata.causationId());
        }
        // Ensures a key exists for every message so partitioning stays deterministic.
        if (metadata.aggregateId() != null) {
            builder.setHeader(org.springframework.kafka.support.KafkaHeaders.KEY,
                    metadata.aggregateId().getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    /**
     * Reads metadata back off a consumed message. Missing headers degrade to
     * sensible defaults so a malformed producer cannot crash a consumer.
     */
    public static EventMetadata read(Headers headers) {
        if (headers == null) {
            return fallback("unknown");
        }
        return new EventMetadata(
                header(headers, EVENT_ID, UUID.randomUUID().toString()),
                header(headers, EVENT_TYPE, "unknown"),
                header(headers, AGGREGATE_TYPE, "unknown"),
                header(headers, AGGREGATE_ID, null),
                header(headers, TENANT_ID, "public"),
                parseInt(header(headers, SCHEMA_VERSION, "1"), 1),
                parseInstant(header(headers, OCCURRED_AT, null)),
                header(headers, CORRELATION_ID, null),
                header(headers, CAUSATION_ID, null));
    }

    /** Reads metadata from a Spring {@link Message} (listener argument form). */
    public static EventMetadata read(org.springframework.messaging.Message<?> message) {
        return read(message.getHeaders());
    }

    /**
     * Reads metadata from Spring {@link org.springframework.messaging.MessageHeaders}.
     *
     * <p>Separate from the Kafka {@link Headers} overload because the two header
     * containers are unrelated types in Java even though both expose
     * {@code lastHeader(String)}.
     */
    public static EventMetadata read(org.springframework.messaging.MessageHeaders headers) {
        if (headers == null) {
            return fallback("unknown");
        }
        return new EventMetadata(
                stringHeader(headers, EVENT_ID, UUID.randomUUID().toString()),
                stringHeader(headers, EVENT_TYPE, "unknown"),
                stringHeader(headers, AGGREGATE_TYPE, "unknown"),
                stringHeader(headers, AGGREGATE_ID, null),
                stringHeader(headers, TENANT_ID, "public"),
                parseInt(stringHeader(headers, SCHEMA_VERSION, "1"), 1),
                parseInstant(stringHeader(headers, OCCURRED_AT, null)),
                stringHeader(headers, CORRELATION_ID, null),
                stringHeader(headers, CAUSATION_ID, null));
    }

    private static String stringHeader(org.springframework.messaging.MessageHeaders headers,
                                       String name, String defaultValue) {
        Object value = headers.get(name);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : value.toString();
    }

    private static EventMetadata fallback(String eventType) {
        return new EventMetadata(UUID.randomUUID().toString(), eventType, "unknown", null,
                "public", 1, Instant.now(), null, null);
    }

    private static String header(Headers headers, String name, String defaultValue) {
        Header header = headers.lastHeader(name);
        if (header == null || header.value() == null) {
            return defaultValue;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }
}
