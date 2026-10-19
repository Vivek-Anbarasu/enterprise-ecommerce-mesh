package com.enterprise.mesh.inventoryservice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lifecycle tests for a stock reservation. */
class InventoryReservationTest {

    private InventoryReservation reservation() {
        return new InventoryReservation("res-1", "SKU-1", "tenant-1", "order-1", 2,
                Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("a new reservation starts pending")
    void startsPending() {
        assertThat(reservation().isPending()).isTrue();
        assertThat(reservation().getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("confirm and release are terminal; a redelivered decision is a no-op")
    void transitionsAreIdempotent() {
        InventoryReservation reservation = reservation();

        assertThat(reservation.confirm()).isTrue();
        assertThat(reservation.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.CONFIRMED);

        // The saga may redeliver the same decision; it must not corrupt state.
        assertThat(reservation.confirm()).isFalse();
        assertThat(reservation.release()).isFalse();
        assertThat(reservation.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a released reservation cannot later be confirmed")
    void releaseWinsOverConfirm() {
        InventoryReservation reservation = reservation();

        assertThat(reservation.release()).isTrue();
        assertThat(reservation.confirm()).isFalse();
        assertThat(reservation.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("a confirmed reservation can be unconfirmed, restoring sellable stock")
    void unconfirmOnlyAppliesToConfirmed() {
        InventoryReservation reservation = reservation();

        // The abandoned-checkout sequence: Step 3 confirmed the hold, Step 4 failed.
        assertThat(reservation.confirm()).isTrue();

        // Release is refused here - this was the window where a rolled-back order
        // left stock permanently stranded.
        assertThat(reservation.release()).isFalse();
        assertThat(reservation.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.CONFIRMED);

        assertThat(reservation.unconfirm()).isTrue();
        assertThat(reservation.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.UNCONFIRMED);
    }

    @Test
    @DisplayName("unconfirm is refused for a reservation that was never confirmed")
    void unconfirmRefusesUnconfirmed() {
        InventoryReservation pending = reservation();

        // Undoing a sale that never happened would create stock out of nothing.
        assertThat(pending.unconfirm()).isFalse();
        assertThat(pending.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("a redelivered unconfirm is a no-op, so stock cannot be inflated")
    void unconfirmIsIdempotent() {
        InventoryReservation reservation = reservation();
        assertThat(reservation.confirm()).isTrue();

        assertThat(reservation.unconfirm()).isTrue();
        // Second delivery must not transition again - InventoryItem.unconfirm does
        // not clamp, so this guard is the only thing preventing runaway onHand.
        assertThat(reservation.unconfirm()).isFalse();
        assertThat(reservation.getStatus())
                .isEqualTo(InventoryReservation.ReservationStatus.UNCONFIRMED);
    }

    @Test
    @DisplayName("an unconfirmed reservation is no longer pending, so the sweeper skips it")
    void unconfirmedIsNotSwept() {
        InventoryReservation reservation = reservation();
        reservation.confirm();
        reservation.unconfirm();

        assertThat(reservation.isPending())
                .as("findExpiredPending filters on PENDING; a non-pending row must not match")
                .isFalse();
        assertThat(reservation.isExpired(Instant.now().plus(30, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    @DisplayName("a reservation whose deadline has not passed is not expired")
    void futureReservationIsNotExpired() {
        assertThat(reservation().isExpired(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("expiry is judged against the supplied clock, not wall-clock now")
    void expiryUsesSuppliedInstant() {
        InventoryReservation pending = reservation();
        // Judged "elapsed" once time advances past the deadline.
        assertThat(pending.isExpired(Instant.now().plus(30, ChronoUnit.MINUTES))).isTrue();
    }

    @Test
    @DisplayName("a finalised reservation is never reported expired, even past its deadline")
    void finalisedReservationIsNotExpired() {
        InventoryReservation confirmed = reservation();
        confirmed.confirm();

        assertThat(confirmed.isExpired(Instant.now().plus(30, ChronoUnit.MINUTES))).isFalse();
    }

    @Test
    @DisplayName("an expiry in the past is rejected at construction")
    void rejectsAlreadyExpiredReservation() {
        assertThatThrownBy(() -> new InventoryReservation("res-3", "SKU-1", "tenant-1",
                "order-1", 1, Instant.now().minusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry must be in the future");
    }

    @Test
    @DisplayName("non-positive quantities are rejected")
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new InventoryReservation("res-4", "SKU-1", "tenant-1",
                "order-1", 0, Instant.now().plusSeconds(600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
