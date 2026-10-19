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

    /**
     * Takes the funds reserved by {@link #authorise}.
     *
     * <p>This is the call that actually debits the shopper. Authorisation only
     * places a hold, so without it a completed checkout leaves the customer with a
     * frozen amount and nothing spent.
     *
     * <p><b>Must be idempotent on {@code idempotencyKey}.</b> Unlike a refund, a
     * capture is not naturally safe to repeat: calling it twice against an
     * authorisation that has already been taken is a second debit. The caller
     * retries after a transport failure precisely because it cannot tell whether
     * the first attempt landed, so the provider must be able to recognise the retry
     * as the same capture rather than a new one.
     *
     * @param idempotencyKey derived per-operation, never the authorisation's own
     *                       key - see {@code PaymentService}. Reusing it would make
     *                       the provider replay the authorisation response instead
     *                       of capturing.
     * @throws com.enterprise.mesh.payment.provider.PaymentProviderUnavailableException
     *         when the outcome is unknown. Never swallow this into a success: the
     *         caller must not record a capture that did not happen.
     */
    void capture(String providerReference, BigDecimal amount, String currency,
                 String idempotencyKey);
}
