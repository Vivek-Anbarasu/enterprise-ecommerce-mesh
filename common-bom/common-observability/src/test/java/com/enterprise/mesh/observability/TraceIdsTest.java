package com.enterprise.mesh.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code trace_id} regression.
 *
 * <p>Before this class existed, three RFC 9457 handlers read
 * {@code MDC.get("trace_id")}. Micrometer Tracing writes {@code traceId}, so the
 * read always returned null and {@code String.valueOf} turned that into the
 * literal string {@code "null"} in every 401, 403 and gateway fallback body.
 * A context-load test cannot see this - it only appears when a real request comes
 * through an unauthenticated path - so it is pinned directly here.
 */
class TraceIdsTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static ObjectProvider<Tracer> provider(Tracer tracer) {
        @SuppressWarnings("unchecked")
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return provider;
    }

    private static Tracer tracerWithContext() {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE_ID);
        when(context.spanId()).thenReturn(SPAN_ID);

        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);

        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(span);
        return tracer;
    }

    @Test
    void readsTraceIdFromTheActiveSpan() {
        TraceIds traceIds = new TraceIds(provider(tracerWithContext()));

        assertThat(traceIds.currentTraceId()).isEqualTo(TRACE_ID);
        assertThat(traceIds.currentSpanId()).isEqualTo(SPAN_ID);
    }

    @Test
    void fallsBackToTheConventionalMdcKeys() {
        // No Tracer bean, but something upstream populated Micrometer's keys.
        MDC.put(TraceIds.MDC_TRACE_ID, TRACE_ID);
        MDC.put(TraceIds.MDC_SPAN_ID, SPAN_ID);

        TraceIds traceIds = new TraceIds(provider(null));

        assertThat(traceIds.currentTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    void doesNotReadTheSnakeCaseKeyAnythingEverWrites() {
        MDC.put("trace_id", TRACE_ID);

        TraceIds traceIds = new TraceIds(provider(null));

        assertThat(traceIds.currentTraceId())
                .as("'trace_id' is not a key Micrometer populates and must be ignored")
                .isNull();
    }

    @Test
    void returnsNullRatherThanTheStringNullWhenNothingIsTraced() {
        TraceIds traceIds = new TraceIds(provider(null));

        assertThat(traceIds.currentTraceId()).isNull();
        assertThat(traceIds.currentSpanId()).isNull();
    }

    @Test
    void applyToOmitsAbsentIdsInsteadOfWritingThem() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", "/api/orders");

        new TraceIds(provider(null)).applyTo(properties);

        assertThat(properties)
                .as("a 401 with no active trace must carry no traceId key at all")
                .containsOnlyKeys("path")
                .doesNotContainValue("null");
    }

    @Test
    void applyToAddsBothIdsWhenTracingIsActive() {
        Map<String, Object> properties = new LinkedHashMap<>();

        new TraceIds(provider(tracerWithContext())).applyTo(properties);

        assertThat(properties).containsEntry("traceId", TRACE_ID).containsEntry("spanId", SPAN_ID);
    }
}
