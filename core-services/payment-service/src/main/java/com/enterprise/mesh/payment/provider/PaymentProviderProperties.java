package com.enterprise.mesh.payment.provider;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the HTTP payment provider, used when
 * {@code mesh.payment.gateway=http}.
 *
 * <p>Deliberately vendor-neutral. {@link HttpPaymentGateway} speaks a small documented
 * JSON contract rather than one provider's dialect, so swapping in a vendor SDK is a
 * matter of adding another {@link com.enterprise.mesh.payment.domain.PaymentGateway}
 * implementation and leaving this class alone. Binding the provider's oddities into the
 * contract would put them in every implementation instead.
 *
 * <p>{@link #apiKey} is a secret and is expected to arrive from an env var backed by a
 * Kubernetes secret, never from a values file. {@link PaymentGatewaySafetyCheck} refuses
 * to start if it is missing, because a payment provider called without credentials fails
 * every transaction at the worst possible moment instead of at deploy time.
 */
@ConfigurationProperties(prefix = "mesh.payment.provider")
@Getter
@Setter
public class PaymentProviderProperties {

    /** Base URL of the provider API, e.g. {@code https://api.payments.example}. */
    private String baseUrl;

    /**
     * Provider API key. Forwarded as {@code Authorization: Bearer ...}.
     * Never logged, never included in an error message.
     */
    private String apiKey;

    /**
     * Connect budget. Short on purpose: by the time a TCP connect to the provider is
     * still pending the transaction is already doomed, and holding the request thread
     * open makes the outage worse rather than less.
     */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Read budget. A provider authorisation is a single round trip, so anything much
     * above this is a stuck connection rather than a slow one. Longer than the caller's
     * own timeout would mean the caller gives up first and retries a call that may still
     * succeed — which is what the idempotency key is for.
     */
    private Duration readTimeout = Duration.ofSeconds(5);
}