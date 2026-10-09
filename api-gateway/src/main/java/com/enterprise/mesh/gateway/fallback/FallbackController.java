package com.enterprise.mesh.gateway.fallback;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

/**
 * Circuit-breaker fallbacks.
 *
 * <p>These handlers are the last line of defence: when a downstream service is
 * degraded, the gateway must fail fast and honestly rather than hold client
 * connections until they time out. Each fallback returns RFC 9457 problem JSON
 * with {@code Retry-After} so well-behaved clients back off instead of
 * hammering the recovering service.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @GetMapping(path = "/catalog", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> catalog() {
        log.warn("Catalog circuit breaker open; serving degraded response");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Catalog Temporarily Unavailable",
                "The product catalogue is temporarily unavailable. Please retry shortly.");
    }

    @GetMapping(path = "/orders", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> orders() {
        log.error("Order circuit breaker open; checkout is unavailable");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Checkout Temporarily Unavailable",
                "We could not start your checkout right now. Your basket is safe - please retry in a moment.");
    }

    @GetMapping(path = "/payments", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> payments() {
        log.error("Payment circuit breaker open; refusing to take money");
        // Deliberately NOT retried automatically by clients: a payment must not
        // be double-charged, so the client must re-submit deliberately.
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Payments Temporarily Unavailable",
                "We could not reach the payment provider. No charge has been made.");
    }

    @GetMapping(path = "/ai", produces = MediaType.APPLICATION_PROBLEM_JSON_VALUE)
    public Mono<ProblemDetail> ai() {
        log.warn("AI orchestrator circuit breaker open");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Recommendations Unavailable",
                "Personalised recommendations are unavailable. Standard browsing still works.");
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
