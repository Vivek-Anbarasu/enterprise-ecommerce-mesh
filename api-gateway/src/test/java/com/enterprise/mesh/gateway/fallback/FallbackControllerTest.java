package com.enterprise.mesh.gateway.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.enterprise.mesh.gateway.filter.BulkheadGatewayFilterFactory;
import com.enterprise.mesh.observability.TraceIds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The carrier fallback has one job above all others: never pretend the update
 * succeeded. A carrier that receives a 2xx stops retrying, and the shipment
 * silently stays in its old state.
 */
class FallbackControllerTest {

    private final FallbackController controller = new FallbackController(TraceIds.none());

    @Test
    @DisplayName("the shipping fallback is a 503 that says the transition was not applied")
    void shippingFallbackFailsHonestly() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/fallback/shipping").build());

        ProblemDetail body = controller.shipping(exchange).block();

        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(body.getTitle()).isEqualTo("Shipping Updates Temporarily Unavailable");
        assertThat(body.getDetail()).contains("not applied");
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("30");
    }

    @Test
    @DisplayName("a bulkhead shed and an open breaker are reported as different faults")
    void shippingFallbackDistinguishesSheddingFromAnOpenBreaker() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/fallback/shipping").build());
        exchange.getAttributes().put(
                BulkheadGatewayFilterFactory.REJECTION_ATTR, "shippingBulkhead");

        ProblemDetail body = controller.shipping(exchange).block();

        assertThat(body).isNotNull();
        assertThat(body.getTitle()).isEqualTo("Shipping Updates At Capacity");
        assertThat(body.getDetail()).contains("not applied");
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("30");
    }
}
