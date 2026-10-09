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
