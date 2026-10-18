package com.enterprise.mesh.payment.domain;

import java.math.BigDecimal;

/**
 * Abstraction over the payment provider.
 *
 * <p>Kept as an interface so the vendor SDK is replaceable and so unit tests
 * never touch the network. Implementations must be safe to call twice with the
 * same {@code idempotencyKey} - the provider's own idempotency support is the
 * real protection against a double charge when this process dies mid-call.
 */
public interface PaymentGateway {

    /**
     * Reserves funds without taking them.
     *
     * @param paymentToken opaque single-use token from the provider's SDK
     * @param idempotencyKey must be forwarded to the provider unchanged
     */
    GatewayResult authorise(String paymentToken, BigDecimal amount,
                            String currency, String idempotencyKey);

    /** Returns captured funds to the customer. */
    void refund(String providerReference, BigDecimal amount, String currency);
}
