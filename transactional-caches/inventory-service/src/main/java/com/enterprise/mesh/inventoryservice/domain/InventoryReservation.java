package com.enterprise.mesh.inventoryservice.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;

/**
 * A time-boxed hold on stock for a specific checkout attempt.
 *
 * <p>A reservation is identified by a client-supplied {@code reservationId} so
 * that a retried request maps to the same row (idempotency). If the shopper
 * abandons checkout, the hold must be reclaimed: {@link ReservationStatus#PENDING}
 * rows past their {@code expiresAt} are swept by a scheduled job and released.
 *
 * <p><b>Read-only, deliberately - and this is the class where it matters most.</b>
 * {@code @Getter} only, no {@code @Setter}, because these fields are precisely
 * what the double-selling protection is built on:
 *
 * <ul>
 *   <li>{@code version} is the {@code @Version} optimistic-locking column. A
 *       setter would let a caller move it by hand and defeat the very mechanism
 *       that makes concurrent reservation attempts safe.</li>
 *   <li>{@code status} only moves through {@link #confirm}, {@link #release} and
 *       {@link #markExpired}, each of which checks {@link #isPending()} first. That
 *       check is what stops a confirmed hold being released back into sellable
 *       stock, or a released one being confirmed - either of which double-sells.</li>
 *   <li>{@code quantity} and {@code expiresAt} are validated in the constructor
 *       (positive quantity, expiry in the future). {@code expiresAt} additionally
 *       drives the expiry sweeper, so a value written out of band would either
 *       strand stock or release it early.</li>
 *   <li>{@code reservationId} is the idempotency key and is mapped
 *       {@code updatable = false}; {@code sku} and {@code tenantId} are part of an
 *       index and are fixed at construction.</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "inventory_reservations", indexes = {
        @Index(name = "idx_reservation_order", columnList = "order_id"),
        @Index(name = "idx_reservation_expiry", columnList = "status,expires_at"),
        @Index(name = "idx_reservation_sku", columnList = "sku,tenant_id")
})
public class InventoryReservation {

    public enum ReservationStatus {
        /** Holding stock; may be confirmed or released. */
        PENDING,
        /** Stock permanently decremented after payment succeeded. */
        CONFIRMED,
        /** Hold returned to available stock. */
        RELEASED,
        /** Hold expired untouched and was swept. */
        EXPIRED,
        /**
         * A confirmed sale that was undone, because the order it belonged to was
         * never persisted. Stock is back on sale.
         *
         * <p>Terminal, like every other state here. The sweeper's query filters on
         * PENDING, so this row is never revisited.
         */
        UNCONFIRMED
    }

    @Id
    @Column(name = "reservation_id", nullable = false, updatable = false, length = 64)
    private String reservationId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected InventoryReservation() {
        // required by JPA
    }

    public InventoryReservation(String reservationId, String sku, String tenantId,
                                String orderId, int quantity, Instant expiresAt) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Reservation expiry must be in the future");
        }
        this.reservationId = reservationId;
        this.sku = sku;
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.quantity = quantity;
        this.status = ReservationStatus.PENDING;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean isPending() {
        return status == ReservationStatus.PENDING;
    }

    public boolean isExpired(Instant now) {
        return isPending() && !expiresAt.isAfter(now);
    }

    /** Transitions to CONFIRMED. Returns false when already finalised. */
    public boolean confirm() {
        if (!isPending()) {
            return false;
        }
        this.status = ReservationStatus.CONFIRMED;
        return true;
    }

    /** Transitions to RELEASED. Returns false when already finalised. */
    public boolean release() {
        if (!isPending()) {
            return false;
        }
        this.status = ReservationStatus.RELEASED;
        return true;
    }

    /**
     * Transitions CONFIRMED to UNCONFIRMED, reversing the stock decrement.
     *
     * <p>Guarded on CONFIRMED specifically, not merely on "not already
     * finalised". {@link #release} accepts any pending hold, but a release here
     * would be wrong: it subtracts {@code reserved} without restoring
     * {@code onHand}, so on a confirmed row it would corrupt the ledger rather
     * than fix it. Only an unconfirm puts physical stock back.
     *
     * <p>This guard is also the only thing stopping a repeated call from
     * inflating stock - {@link InventoryItem#unconfirm} deliberately does not
     * clamp, because there is nothing to clamp against.
     *
     * @return false when the reservation is not CONFIRMED, including a second call
     */
    public boolean unconfirm() {
        if (status != ReservationStatus.CONFIRMED) {
            return false;
        }
        this.status = ReservationStatus.UNCONFIRMED;
        return true;
    }

    /** Transitions to EXPIRED. Returns false when already finalised. */
    public boolean markExpired() {
        if (!isPending()) {
            return false;
        }
        this.status = ReservationStatus.EXPIRED;
        return true;
    }

    public String attachOrder(String orderId) {
        this.orderId = orderId;
        return orderId;
    }
}
