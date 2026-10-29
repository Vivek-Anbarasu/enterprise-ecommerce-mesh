package com.enterprise.mesh.order.saga;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.enterprise.mesh.order.saga.SagaExceptions.PaymentDeclinedException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The payment half of the checkout saga.
 *
 * <p>Authorise places a hold, capture takes the money, void releases an unused
 * hold and refund returns captured funds. The reference returned by authorise is
 * the payment id used by every later call.
 *
 * <p>A decline is a 200 with {@code approved=false}, so it is evaluated outside
 * the circuit breaker: a run of declined cards must not look like a broken
 * payment service and open the breaker on everyone else.
 *
 * <p>All calls are idempotent at the payment end (authorise on the order id,
 * capture on a derived key), so retrying after a timeout cannot double-charge.
 */
@Component
@Slf4j
public class PaymentSagaClient {

    static final String BREAKER_NAME = "paymentCircuitBreaker";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final String baseUri;

    public PaymentSagaClient(RestClient sagaRestClient,
                             CircuitBreakerRegistry circuitBreakerRegistry,
                             @Value("${mesh.clients.payment}") String paymentUri) {
        this.restClient = sagaRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(BREAKER_NAME);
        this.baseUri = stripTrailingSlash(paymentUri);
    }

    /**
     * Authorises funds and returns the payment reference.
     *
     * @throws PaymentDeclinedException when the provider declines
     */
    public String authorise(String orderId, BigDecimal amount, String currency,
                            String paymentToken, String tenantId) {
        PaymentAuthorisation authorisation = circuitBreaker.executeSupplier(() -> restClient.post()
                .uri(baseUri + "/api/payments/authorisations")
                .header("X-Tenant-ID", tenantId)
                .header("Idempotency-Key", orderId)
                .body(new AuthorisationRequest(orderId, amount, currency, paymentToken))
                .retrieve()
                .body(PaymentAuthorisation.class));

        if (authorisation == null || !authorisation.approved()) {
            throw new PaymentDeclinedException(
                    authorisation == null ? "empty provider response" : authorisation.declineReason());
        }
        return authorisation.reference();
    }

    /** Takes the funds that were authorised. */
    public void capture(String paymentReference, String tenantId) {
        circuitBreaker.executeRunnable(() -> restClient.post()
                .uri(baseUri + "/api/payments/{id}/capture", paymentReference)
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .toBodilessEntity());
        log.info("Captured payment {}", paymentReference);
    }

    /** Releases an authorisation without taking money. */
    public void voidAuthorisation(String paymentReference, String tenantId) {
        circuitBreaker.executeRunnable(() -> restClient.post()
                .uri(baseUri + "/api/payments/{id}/void", paymentReference)
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .toBodilessEntity());
        log.info("Voided authorisation {}", paymentReference);
    }

    /** Returns money that was already captured. */
    public void refund(String paymentReference, String tenantId) {
        circuitBreaker.executeRunnable(() -> restClient.post()
                .uri(baseUri + "/api/payments/{id}/refund", paymentReference)
                .header("X-Tenant-ID", tenantId)
                .retrieve()
                .toBodilessEntity());
        log.info("Refunded payment {}", paymentReference);
    }

    private static String stripTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    // --------------------------------------------------------------- payloads

    private record AuthorisationRequest(String orderId, BigDecimal amount,
                                       String currency, String paymentToken) {
    }

    private record PaymentAuthorisation(String reference, boolean approved, String declineReason) {
    }
}
