package com.enterprise.mesh.order.saga;

/**
 * Failures the checkout saga distinguishes.
 *
 * <p>They live here rather than nested in the orchestrator because the remote
 * clients raise them; a client importing the orchestrator to name its own
 * failures would invert the dependency.
 */
public final class SagaExceptions {

    private SagaExceptions() {
    }

    /** Raised on a 409 from inventory: the units are not available. */
    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String message) {
            super(message);
        }
    }

    /** Raised when the provider declines, or returns an unusable answer. */
    public static class PaymentDeclinedException extends RuntimeException {
        public PaymentDeclinedException(String reason) {
            super("Payment declined: " + reason);
        }
    }

    /**
     * A participant failed in a way that is not a business outcome: timeout,
     * connection reset, 5xx, or an open circuit breaker.
     *
     * <p>Separate from the two above so the saga can tell "sold out" (expected,
     * no compensation) from "inventory is down" (also no compensation, but a
     * different metric and log level).
     */
    public static class RemoteCallException extends RuntimeException {
        public RemoteCallException(String message) {
            super(message);
        }
    }

    /**
     * The order left the in-flight states before this orchestrator finished -
     * usually because a recovery sweep failed it while the request was still
     * running. The orchestrator stops and reports the stored outcome instead of
     * fighting the sweep for the row.
     */
    public static class SagaAbandonedException extends RuntimeException {
        public SagaAbandonedException(String message) {
            super(message);
        }
    }
}
