package com.enterprise.mesh.gateway.fallback;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.enterprise.mesh.gateway.filter.BulkheadGatewayFilterFactory;
import reactor.core.publisher.Mono;

/**
 * Circuit-breaker and bulkhead fallbacks.
 *
 * <p>These handlers are the last line of defence: when a downstream service is
 * degraded, or when a route has hit its concurrency ceiling, the gateway must fail
 * fast and honestly rather than hold client connections until they time out. Each
 * fallback returns RFC 9457 problem JSON with {@code Retry-After} so well-behaved
 * clients back off instead of hammering the recovering service.
 *
 * <p>Mapped with {@link RequestMapping} rather than {@code @GetMapping}. The gateway
 * forwards the original request here with its method and body untouched, so a
 * {@code POST /api/payments/...} or {@code POST /api/orders/...} arrives as a POST.
 * Restricting these to GET would answer every checkout failure with a bare 405 and no
 * problem body at all - the client would be told its request was malformed rather than
 * that the service was unavailable, and would have nothing to back off from.
 */
@RestController
@RequestMapping("/fallback")
@Slf4j
public class FallbackController {

    @RequestMapping(path = "/catalog", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> catalog(ServerWebExchange exchange) {
        if (shedBy(exchange, "catalog")) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Catalogue At Capacity",
                    "The product catalogue is at capacity right now. Please retry shortly.");
        }
        log.warn("Catalog circuit breaker open; serving degraded response");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Catalog Temporarily Unavailable",
                "The product catalogue is temporarily unavailable. Please retry shortly.");
    }

    @RequestMapping(path = "/orders", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> orders(ServerWebExchange exchange) {
        if (shedBy(exchange, "order")) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Checkout At Capacity",
                    "Checkout is at capacity right now and your request was not submitted. "
                            + "Your basket is safe - please retry in a moment.");
        }
        log.error("Order circuit breaker open; checkout is unavailable");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Checkout Temporarily Unavailable",
                "We could not start your checkout right now. Your basket is safe - please retry in a moment.");
    }

    @RequestMapping(path = "/payments", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> payments(ServerWebExchange exchange) {
        if (shedBy(exchange, "payment")) {
            // Same "no charge has been made" contract as below, for the same reason: a
            // shed request never reached the payment service, so nothing can have been
            // taken. The client must still re-submit deliberately rather than retry.
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Payments At Capacity",
                    "Payment processing is at capacity right now. No charge has been made.");
        }
        log.error("Payment circuit breaker open; refusing to take money");
        // Deliberately NOT retried automatically by clients: a payment must not
        // be double-charged, so the client must re-submit deliberately.
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Payments Temporarily Unavailable",
                "We could not reach the payment provider. No charge has been made.");
    }

    @RequestMapping(path = "/ai", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> ai(ServerWebExchange exchange) {
        if (shedBy(exchange, "ai")) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "Recommendations Busy",
                    "Personalised recommendations are at capacity. Standard browsing still works.");
        }
        log.warn("AI orchestrator circuit breaker open");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Recommendations Unavailable",
                "Personalised recommendations are unavailable. Standard browsing still works.");
    }

    /**
     * True when this request was shed by the named route's bulkhead rather than
     * failing its circuit breaker.
     *
     * <p>The two are different faults and are fixed differently. A shed request means
     * the route is at its concurrency ceiling while the dependency is answering - the
     * circuit is closed and healthy, and the fix is capacity, not failover. Reporting
     * one as the other sends whoever is on call to investigate a circuit breaker that
     * has nothing wrong with it.
     */
    private boolean shedBy(ServerWebExchange exchange, String route) {
        String bulkhead = exchange.getAttribute(BulkheadGatewayFilterFactory.REJECTION_ATTR);
        if (bulkhead == null) {
            return false;
        }
        log.warn("{} bulkhead '{}' is full; request shed before reaching the backend", route, bulkhead);
        return true;
    }

    private Mono<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(org.springframework.web.util.UriComponentsBuilder
                .fromPath("https://errors.enterprise-ecommerce.com/{title}")
                .buildAndExpand(title.toLowerCase()).toUri());
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("traceId", org.slf4j.MDC.get("trace_id"));
        return Mono.just(problem);
    }
}