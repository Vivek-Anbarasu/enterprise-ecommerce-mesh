package com.enterprise.mesh.observability;

import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

/**
 * Resolves the active trace id for inclusion in error responses.
 *
 * <p><strong>Why this exists.</strong> Three RFC 9457 handlers read
 * {@code MDC.get("trace_id")}. Nothing in this repository ever writes that key.
 * Micrometer Tracing's {@code TracingMDCScope} writes {@code traceId} and
 * {@code spanId} — camelCase, no separator — so every 401, 403 and gateway
 * fallback was returning the literal string {@code "null"} to the caller. A
 * support engineer handed that string cannot find the trace, and the handler that
 * was supposed to make failures diagnosable was the thing making them opaque.
 *
 * <p><strong>Why the {@link Tracer} and not MDC.</strong> MDC is a plain
 * {@link ThreadLocal}. In the reactive gateway the span lives on a Reactor thread
 * that may never have run the servlet filters, so an MDC read there returns
 * nothing even once the key is spelled correctly. Micrometer wraps its current
 * context in {@code ReactorCurrentTraceContext} whenever
 * {@code context-propagation} is on the classpath — which
 * {@code spring-boot-starter-actuator} guarantees — so {@code currentSpan()}
 * resolves the span from the Reactor context across thread hops. The MDC lookup
 * remains only as a fallback for the case where tracing is switched off but
 * something upstream still populated the conventional keys.
 *
 * <p>Every accessor returns {@code null} rather than a placeholder when nothing
 * is being traced. Callers must omit the property entirely; writing the string
 * {@code "null"} into a problem body is the bug this class exists to remove.
 */
public class TraceIds {

    /**
     * MDC keys written by Micrometer Tracing's {@code TracingMDCScope}. Named
     * here because they are not exposed as public constants upstream, and
     * getting one wrong is silent — the read simply yields {@code null}.
     */
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";

    private final Tracer tracer;

    private TraceIds(@Nullable Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * @param tracerProvider resolved tolerating absence: tracing is optional, and
     *                       a service with no {@link Tracer} bean must still start
     *                       and still return problem bodies.
     */
    public TraceIds(ObjectProvider<Tracer> tracerProvider) {
        this(tracerProvider.getIfAvailable());
    }

    /**
     * An instance that never reports a trace.
     *
     * <p>For the case where the observability auto-configuration is not in play
     * at all — a security-only context, or a slice test that deliberately loads
     * one auto-configuration in isolation. Those must keep working: the trace id
     * is a convenience for whoever reads the error, and its absence must not
     * become a wiring failure that stops a pod booting.
     */
    public static TraceIds none() {
        return new TraceIds((Tracer) null);
    }

    /**
     * @return the active trace id, or {@code null} when there is no active span.
     */
    public String currentTraceId() {
        Span span = currentSpan();
        if (span != null) {
            String traceId = blankToNull(span.context().traceId());
            if (traceId != null) {
                return traceId;
            }
        }
        return blankToNull(MDC.get(MDC_TRACE_ID));
    }

    /**
     * @return the active span id, or {@code null} when there is no active span.
     */
    public String currentSpanId() {
        Span span = currentSpan();
        if (span != null) {
            String spanId = blankToNull(span.context().spanId());
            if (spanId != null) {
                return spanId;
            }
        }
        return blankToNull(MDC.get(MDC_SPAN_ID));
    }

    /**
     * Adds {@code traceId} and {@code spanId} to a problem-detail property map,
     * omitting either one that is absent rather than writing a null or the
     * string {@code "null"} into the response.
     *
     * @param properties mutable map; {@link java.util.Map#of()} cannot be used
     *                   with this because it rejects null values
     */
    public void applyTo(Map<String, Object> properties) {
        String traceId = currentTraceId();
        if (traceId != null) {
            properties.put("traceId", traceId);
        }
        String spanId = currentSpanId();
        if (spanId != null) {
            properties.put("spanId", spanId);
        }
    }

    private Span currentSpan() {
        return tracer == null ? null : tracer.currentSpan();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
