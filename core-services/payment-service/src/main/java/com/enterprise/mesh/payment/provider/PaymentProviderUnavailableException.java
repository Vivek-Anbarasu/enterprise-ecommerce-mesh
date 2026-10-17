package com.enterprise.mesh.payment.provider;

/**
 * Thrown when the payment provider could not be reached, or answered with something
 * other than a decision.
 *
 * <p>Distinct from a decline, and the distinction is load-bearing. A decline means the
 * provider considered the transaction and refused it; that outcome is known, safe to
 * persist, and should be shown to the shopper as a card failure. This exception means
 * the outcome is <em>unknown</em>: a timeout, a connection reset, a 502, a body that did
 * not parse. The payment may or may not have been taken.
 *
 * <p>So it propagates rather than being converted into a decline. {@code PaymentService}
 * is transactional, so throwing rolls the attempt back without persisting a {@code Payment}
 * row, and the caller retries with the same {@code idempotencyKey}. The provider
 * recognises the repeat and returns the original outcome instead of charging twice —
 * which only works because that key is forwarded unchanged.
 *
 * <p>Mapping this onto a decline is the failure mode worth naming: the shopper is told
 * their card was refused, no order is fulfilled, and the money is nonetheless gone.
 */
public class PaymentProviderUnavailableException extends RuntimeException {

    /** Idempotency key or provider reference this failure relates to. For logs. */
    private final String handle;

    public PaymentProviderUnavailableException(String message, String handle) {
        super(message);
        this.handle = handle;
    }

    public String getHandle() {
        return handle;
    }
}