package com.enterprise.mesh.payment.provider;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

import com.enterprise.mesh.payment.domain.PaymentGateway;
import com.enterprise.mesh.payment.domain.PaymentService;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Real payment provider over HTTP, enabled when {@code mesh.payment.gateway=http}.
 *
 * <p><b>The contract it speaks</b> — deliberately small and vendor-neutral:
 *
 * <pre>
 * POST {baseUrl}/payments/authorisations
 *   Authorization: Bearer {apiKey}
 *   Idempotency-Key: {idempotencyKey}          &lt;- forwarded unchanged
 *   Content-Type: application/json
 *   {"paymentToken":"tok_...","amount":129.99,"currency":"GBP"}
 *
 *   200 {"status":"authorised","providerReference":"pi_123","instrumentLabel":"Visa ****4242"}
 *   200 {"status":"declined","declineReason":"insufficient_funds"}
 *
 * POST {baseUrl}/payments/refunds
 *   Authorization: Bearer {apiKey}
 *   Idempotency-Key: {idempotencyKey}
 *   {"providerReference":"pi_123","amount":129.99,"currency":"GBP"}
 *   204
 * </pre>
 *
 * <p><b>Three things this class is careful about.</b>
 *
 * <ol>
 *   <li><b>The idempotency key is forwarded byte-for-byte.</b> {@link PaymentGateway}'s
 *       contract requires it, and it is the only thing standing between a network
 *       timeout and a double charge. {@code PaymentService} replays a stored outcome on a
 *       repeat key rather than calling out again, but that replay only works if the
 *       previous call actually reached the provider — so the provider must see the same
 *       key to recognise the retry as the same charge.</li>
 *   <li><b>A transport failure is not a decline.</b> A decline is a business outcome and
 *       is persisted as one. A timeout, a 5xx or a connection reset means the outcome is
 *       <em>unknown</em>, so this propagates {@link PaymentProviderUnavailableException}
 *       instead of returning a decline. That distinction is the whole reason the class
 *       exists: mapping "I could not reach the provider" onto "the card was refused"
 *       would silently discard a payment that may well have succeeded, and the shopper
 *       would be told their card failed while money was taken.</li>
 *   <li><b>Nothing sensitive is logged.</b> No payment token, no API key. The
 *       idempotency key is logged because it is a correlation handle, not a credential.</li>
 * </ol>
 *
 * @see StubPaymentGateway for the sandbox, and PaymentGatewaySafetyCheck for the
 *      startup validation that guarantees this bean is only ever active when it is
 *      fully configured.
 */
@Component
@ConditionalOnProperty(name = "mesh.payment.gateway", havingValue = "http")
@Slf4j
public class HttpPaymentGateway implements PaymentGateway {

    /** Must match CorrelationIdFilter.HEADER in common-observability. */
    private static final String CORRELATION_HEADER = "X-Request-ID";

    private final RestClient client;

    public HttpPaymentGateway(RestClient.Builder builder, PaymentProviderProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "mesh.payment.gateway=http but mesh.payment.provider.base-url is unset. Set it, "
                            + "e.g. --set services.payment-service.extraEnv.PAYMENT_PROVIDER_BASE_URL=...");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "mesh.payment.gateway=http but mesh.payment.provider.api-key is unset. Every "
                            + "provider call would be rejected, which fails each payment at the "
                            + "worst possible moment rather than at deploy time. Supply it from a "
                            + "Kubernetes secret, never from values.yaml.");
        }

        // Connect and read budgets are applied by PaymentProviderClientConfig, a
        // RestClientCustomizer that Boot has already applied to the injected builder.
        // Deliberately not set here: overriding the request factory would discard
        // whatever the builder already carries.
        this.client = builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .requestInterceptor((request, body, execution) -> {
                    // Read per request rather than captured once, which is the whole
                    // reason CorrelationIdFilter puts it in the MDC.
                    String requestId = MDC.get("request_id");
                    if (requestId != null && !requestId.isBlank()) {
                        request.getHeaders().set(CORRELATION_HEADER, requestId);
                    }
                    return execution.execute(request, body);
                })
                .build();

        log.info("HTTP payment provider configured: baseUrl={} connectTimeout={} readTimeout={}",
                properties.getBaseUrl(), properties.getConnectTimeout(), properties.getReadTimeout());
    }

    @Override
    public PaymentService.GatewayResult authorise(String paymentToken, BigDecimal amount,
                                                  String currency, String idempotencyKey) {
        AuthorisationResponse response = client.post()
                .uri("/payments/authorisations")
                // Forwarded unchanged. See the class javadoc: this is the protection
                // against a double charge, not a convenience header.
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AuthorisationRequest(paymentToken, amount, currency))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, res) -> {
                    throw unavailable(idempotencyKey, res.getStatusCode().value());
                })
                .body(AuthorisationResponse.class);

        if (response == null) {
            throw new PaymentProviderUnavailableException(
                    "Provider returned an empty authorisation body for key " + idempotencyKey, idempotencyKey);
        }
        if (response.declined()) {
            log.info("Provider declined key={} reason={}", idempotencyKey, response.declineReason());
            return PaymentService.GatewayResult.declined(response.declineReason());
        }
        log.info("Provider authorised key={} reference={} amount={} {}",
                idempotencyKey, response.providerReference(), amount, currency);
        return PaymentService.GatewayResult.approved(response.providerReference(), response.instrumentLabel());
    }

    @Override
    public void refund(String providerReference, BigDecimal amount, String currency) {
        // The refund has no idempotency key of its own in this contract; the
        // provider reference is the handle, and PaymentService guarantees this is
        // called at most once per payment by short-circuiting an already-refunded row.
        client.post()
                .uri("/payments/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RefundRequest(providerReference, amount, currency))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, res) -> {
                    throw unavailable(providerReference, res.getStatusCode().value());
                })
                .toBodilessEntity();

        log.info("Provider refunded reference={} amount={} {}", providerReference, amount, currency);
    }

    /**
     * The provider's status is the only thing worth reporting.
     *
     * <p>The response body is deliberately not read: on a 401 or 403 it can echo the
     * credential we just sent, and this exception ends up in logs and in a problem
     * document. A provider that needs richer diagnostics belongs behind its own SDK,
     * where that redaction can be done properly.
     */
    private PaymentProviderUnavailableException unavailable(String handle, int status) {
        // WARN carries the handle, which is an idempotency key or provider reference -
        // a correlation handle, not a credential.
        log.warn("Payment provider call failed handle={} status={}", handle, status);
        return new PaymentProviderUnavailableException(
                "Payment provider returned HTTP " + status + " for " + handle, handle);
    }

    // Wire shapes. Records so Jackson needs no extra annotations.

    private record AuthorisationRequest(String paymentToken, BigDecimal amount, String currency) {
    }

    private record RefundRequest(String providerReference, BigDecimal amount, String currency) {
    }

    private record AuthorisationResponse(String status, String providerReference,
                                         String instrumentLabel, String declineReason) {

        boolean declined() {
            return "declined".equalsIgnoreCase(status);
        }
    }
}