package com.enterprise.mesh.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the step-to-undo mapping.
 *
 * <p>This mapping is the contract that lets the live failure path, the recovery
 * sweep and the database agree on what has to be undone. Getting one row wrong
 * is expensive in a specific way: a missing {@code UNCONFIRM_STOCK} leaves stock
 * permanently decremented for an order that does not exist, and it is invisible
 * until an inventory count disagrees.
 */
class CompensationActionTest {

    @Test
    @DisplayName("nothing committed means nothing to undo")
    void startedNeedsNoCompensation() {
        assertThat(CompensationAction.forStep(SagaStep.STARTED)).isEmpty();
    }

    @Test
    @DisplayName("a stock hold is released, not unconfirmed")
    void reservedReleasesTheHold() {
        assertThat(CompensationAction.forStep(SagaStep.STOCK_RESERVED))
                .containsExactly(CompensationAction.RELEASE_STOCK);
    }

    @Test
    @DisplayName("an authorisation is voided before the hold is released")
    void authorisedVoidsThenReleases() {
        assertThat(CompensationAction.forStep(SagaStep.PAYMENT_AUTHORISED))
                .containsExactly(CompensationAction.VOID_PAYMENT,
                        CompensationAction.RELEASE_STOCK);
    }

    @Test
    @DisplayName("a confirmed hold needs unconfirm, because release would no-op")
    void confirmedUnconfirms() {
        assertThat(CompensationAction.forStep(SagaStep.STOCK_CONFIRMED))
                .containsExactly(CompensationAction.VOID_PAYMENT,
                        CompensationAction.UNCONFIRM_STOCK);
    }

    @Test
    @DisplayName("captured money is refunded, not voided")
    void capturedRefundsThenUnconfirms() {
        assertThat(CompensationAction.forStep(SagaStep.PAYMENT_CAPTURED))
                .containsExactly(CompensationAction.REFUND_PAYMENT,
                        CompensationAction.UNCONFIRM_STOCK);
    }

    @Test
    @DisplayName("a completed checkout is never compensated")
    void completedNeedsNoCompensation() {
        assertThat(CompensationAction.forStep(SagaStep.COMPLETED)).isEmpty();
    }

    @Test
    @DisplayName("stock actions target a reservation; money actions target a payment")
    void actionsKnowTheirTarget() {
        assertThat(CompensationAction.RELEASE_STOCK.usesReservation()).isTrue();
        assertThat(CompensationAction.UNCONFIRM_STOCK.usesReservation()).isTrue();
        assertThat(CompensationAction.VOID_PAYMENT.usesReservation()).isFalse();
        assertThat(CompensationAction.REFUND_PAYMENT.usesReservation()).isFalse();
    }
}
