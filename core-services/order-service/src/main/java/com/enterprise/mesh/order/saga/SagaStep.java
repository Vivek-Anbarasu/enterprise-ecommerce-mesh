package com.enterprise.mesh.order.saga;

/**
 * How far the checkout orchestration has progressed.
 *
 * <p>Declared in execution order: the durable step only ever moves forward, so
 * {@link #hasReached(SagaStep)} is a plain ordinal comparison. The enum is the
 * single source of truth for "what has already been committed remotely", which
 * is what makes a crashed checkout recoverable and compensatable.
 *
 * <p>Deliberately finer-grained than {@code Order.OrderStatus}. An order stays
 * {@code RESERVED} from the stock hold until the final commit, while the step
 * distinguishes "payment authorised" from "stock confirmed" from "payment
 * captured" - the difference decides which compensating actions are legal.
 */
public enum SagaStep {

    /** Order row and saga row written; no remote call has succeeded yet. */
    STARTED,

    /** Inventory holds the units. Compensation: release the hold. */
    STOCK_RESERVED,

    /** Payment authorised. Compensation: void, then release the hold. */
    PAYMENT_AUTHORISED,

    /** Hold converted to a sold decrement. Compensation: void, then unconfirm. */
    STOCK_CONFIRMED,

    /** Money taken. Compensation: refund, then unconfirm. */
    PAYMENT_CAPTURED,

    /** Order persisted as PAID and {@code order.paid} written to the outbox. */
    COMPLETED;

    public boolean hasReached(SagaStep other) {
        return ordinal() >= other.ordinal();
    }

    public boolean isTerminal() {
        return this == COMPLETED;
    }
}
