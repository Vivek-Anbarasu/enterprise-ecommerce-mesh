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

/**
 * A time-boxed hold on stock for a specific checkout attempt.
 *
 * <p>A reservation is identified by a client-supplied {@code reservationId} so
 * that a retried request maps to the same row (idempotency). If the shopper
 * abandons checkout, the hold must be reclaimed: {@link ReservationStatus#PENDING}
 * rows past their {@code expiresAt} are swept by a scheduled job and released.
 */
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
        EXPIRED
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

    public String getReservationId() {
        return reservationId;
    }

    public String getSku() {
        return sku;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOrderId() {
        return orderId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
