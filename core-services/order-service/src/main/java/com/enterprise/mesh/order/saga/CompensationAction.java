package com.enterprise.mesh.order.saga;

import java.util.List;

/**
 * Undo actions the orchestrator can queue against a failed checkout.
 *
 * <p>Which actions are legal is a function of {@link SagaStep}, not of the
 * exception that caused the failure: the step records what actually committed
 * remotely, and {@link #forStep(SagaStep)} maps that to the smallest set that
 * restores a clean state. Doing it this way means the live failure path, the
 * restart-recovery sweep and the database itself cannot disagree about what
 * needs undoing.
 */
public enum CompensationAction {

    /** Drop a stock hold that was never confirmed. */
    RELEASE_STOCK(true),

    /** Return units that were already decremented by a confirm. */
    UNCONFIRM_STOCK(true),

    /** Release an authorisation before any money moved. */
    VOID_PAYMENT(false),

    /** Return money that was already captured. */
    REFUND_PAYMENT(false);

    private final boolean usesReservation;

    CompensationAction(boolean usesReservation) {
        this.usesReservation = usesReservation;
    }

    /** True when {@code target_ref} is a reservation id rather than a payment id. */
    public boolean usesReservation() {
        return usesReservation;
    }

    /**
     * The compensating actions owed for a checkout that got as far as {@code step}.
     *
     * <p>Ordered so money is protected first: void/refund before returning stock,
     * because a failure to return stock is recoverable by hand while an
     * unauthorised charge is a customer-visible incident.
     */
    public static List<CompensationAction> forStep(SagaStep step) {
        return switch (step) {
            case STARTED -> List.of();
            case STOCK_RESERVED -> List.of(RELEASE_STOCK);
            case PAYMENT_AUTHORISED -> List.of(VOID_PAYMENT, RELEASE_STOCK);
            case STOCK_CONFIRMED -> List.of(VOID_PAYMENT, UNCONFIRM_STOCK);
            case PAYMENT_CAPTURED -> List.of(REFUND_PAYMENT, UNCONFIRM_STOCK);
            case COMPLETED -> List.of();
        };
    }
}
