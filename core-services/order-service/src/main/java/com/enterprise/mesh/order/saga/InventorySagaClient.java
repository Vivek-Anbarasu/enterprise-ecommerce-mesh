package com.enterprise.mesh.order.saga;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.enterprise.mesh.order.saga.SagaExceptions.InsufficientStockException;
import com.enterprise.mesh.order.saga.SagaExceptions.RemoteCallException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The inventory half of the checkout saga.
 *
 * <p>Reserve takes a hold, confirm converts it to a sold decrement, and
 * release/unconfirm undo those two in that order. Every call is idempotent at
 * the inventory end, which is what lets the orchestrator and the compensation
 * worker retry without bookkeeping on this side.
 *
 * <p>Reserve deliberately bypasses {@code retrieve()}'s error translation: a 409
 * is a normal "sold out" answer, not a health signal, and letting it throw inside
 * the circuit breaker would count a busy sale as an inventory outage. The
 * breaker therefore sees only transport failures and 5xx.
 */
@Component
@Slf4j
public class InventorySagaClient {

    static final String BREAKER_NAME = "inventoryCircuitBreaker";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final String baseUri;

    public InventorySagaClient(RestClient sagaRestClient,
                               CircuitBreakerRegistry circuitBreakerRegistry,
                               @Value("${mesh.clients.inventory}") String inventoryUri) {
        this.restClient = sagaRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
        this.baseUri = stripTrailingSlash(inventoryUri);
    }

    /**
     * Holds stock for the checkout.
     *
     * @throws InsufficientStockException when inventory answers 409
     */
    public void reserve(String reservationId, String orderId, List<StockLine> lines,
                        Instant expiresAt, String tenantId, String requestId) {
        int status = circuitBreaker.executeSupplier(() -> {
            int code = restClient.post()
                    .uri(baseUri + "/api/inventory/reservations")
                    .header("X-Tenant-ID", tenantId)
                    .header("X-Request-ID", requestId)
                    .header("Idempotency-Key", reservationId)
                    .body(new ReserveRequest(reservationId, orderId, lines, expiresAt))
                    .exchange((request, response) -> response.getStatusCode().value());
            if (code >= 500) {
                throw new RemoteCallException("inventory reserve returned " + code);
            }
            return code;
        });

        if (status == 409) {
            throw new InsufficientStockException(
                    "Stock reservation rejected for order " + orderId);
        }
        if (status >= 300) {
            throw new RemoteCallException("inventory reserve returned " + status);
        }
        log.debug("Reserved stock for order {} as {}", orderId, reservationId);
    }

    /** Turns the hold into a sold decrement. */
    public void confirm(String reservationId, String tenantId) {
        circuitBreaker.executeRunnable(() -> restClient.post()
                .uri(baseUri + "/api/inventory/reservations/{id}/confirm", reservationId)
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .toBodilessEntity());
        log.debug("Confirmed reservation {}", reservationId);
    }

    /** Drops a hold that was never confirmed. */
    public void release(String reservationId, String tenantId) {
        circuitBreaker.executeRunnable(() -> restClient.post()
                .uri(baseUri + "/api/inventory/reservations/{id}/release", reservationId)
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .toBodilessEntity());
        log.info("Released reservation {}", reservationId);
    }

    /** Returns the units of a hold that was already confirmed. */
    public void unconfirm(String reservationId, String tenantId) {
        circuitBreaker.executeRunnable(() -> restClient.post()
                .uri(baseUri + "/api/inventory/reservations/{id}/unconfirm", reservationId)
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .toBodilessEntity());
        log.info("Unconfirmed reservation {} and returned its units", reservationId);
    }

    private static String stripTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    // --------------------------------------------------------------- payloads

    public record StockLine(String sku, int quantity) {
    }

    private record ReserveRequest(String reservationId, String orderId,
                                  List<StockLine> lines, Instant expiresAt) {
    }
}
