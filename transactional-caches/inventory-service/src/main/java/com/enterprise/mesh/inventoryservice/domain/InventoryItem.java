package com.enterprise.mesh.inventoryservice.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;

/**
 * Sellable stock for one SKU at one location.
 *
 * <p>{@code onHand} is physical stock, {@code reserved} is stock held by open
 * reservations, and {@code available = onHand - reserved} is what a shopper may
 * actually buy. Keeping {@code reserved} separate (rather than decrementing
 * {@code onHand} on reserve) means a reservation can be released without having
 * to guess how much to put back, and abandoned carts do not permanently
 * cannibalise availability.
 *
 * <p><b>Read-only, deliberately.</b> {@code @Getter} only, no {@code @Setter},
 * because {@code onHand}, {@code reserved} and {@code safetyStock} are a ledger
 * that only ever moves as a balanced pair:
 *
 * <ul>
 *   <li>Every mutation goes through {@link #reserve}, {@link #release},
 *       {@link #confirm}, {@link #unconfirm} or {@link #receive}. Each validates
 *       its argument and then calls {@code touch()}. A setter would bypass all
 *       five.</li>
 *   <li>{@link #confirm} is the point revenue is recognised, and it moves
 *       {@code reserved} and {@code onHand} <em>together</em>. A setter on either
 *       would let stock be decremented without releasing the hold, or a hold
 *       released without decrementing stock - either corrupts the ledger and
 *       eventually double-sells.</li>
 *   <li>{@link #unconfirm} is the only path that puts {@code onHand} back after a
 *       sale, and it is reachable only from a CONFIRMED reservation. A setter would
 *       make stock creation trivial by comparison.</li>
 *   <li>{@link #release} and {@link #confirm} clamp at zero precisely so a
 *       double-release cannot drive the counter negative. That protection lives in
 *       the method body and is gone the moment a setter exists.</li>
 *   <li>{@code version} is the {@code @Version} optimistic-locking column; a
 *       setter would defeat the concurrency control the reservation flow relies
 *       on.</li>
 *   <li>{@code updatedAt} only ever moves via {@code touch()}, so it always
 *       reflects a real stock movement rather than an incidental write.</li>
 * </ul>
 *
 * <p>{@link #available()} stays a method, not a generated getter: it is derived
 * from three fields and floored at zero, so materialising it as a value would
 * introduce a fourth field that can disagree with the three it is computed from.
 */
@Getter
@Entity
@Table(name = "inventory_items", indexes = {
        @Index(name = "idx_inventory_sku_tenant", columnList = "sku,tenant_id"),
        @Index(name = "idx_inventory_tenant", columnList = "tenant_id")
})
public class InventoryItem {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private String id;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** Fulfilling warehouse/fulfilment centre code. */
    @Column(name = "location_code", nullable = false, length = 32)
    private String locationCode;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    /** Units held by reservations that have neither been confirmed nor released. */
    @Column(name = "reserved", nullable = false)
    private int reserved;

    /** Safety margin never offered to shoppers, e.g. for damaged-stock shrinkage. */
    @Column(name = "safety_stock", nullable = false)
    private int safetyStock;

    @Column(name = "version", nullable = false)
    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryItem() {
        // required by JPA
    }

    public InventoryItem(String id, String sku, String tenantId, String locationCode,
                         int onHand, int safetyStock) {
        if (onHand < 0) {
            throw new IllegalArgumentException("onHand must not be negative");
        }
        if (safetyStock < 0) {
            throw new IllegalArgumentException("safetyStock must not be negative");
        }
        this.id = id;
        this.sku = sku;
        this.tenantId = tenantId;
        this.locationCode = locationCode;
        this.onHand = onHand;
        this.safetyStock = safetyStock;
        this.reserved = 0;
        this.updatedAt = Instant.now();
    }

    /** Units a shopper can buy right now, never dipping below the safety stock. */
    public int available() {
        return Math.max(0, onHand - reserved - safetyStock);
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && available() >= quantity;
    }

    /**
     * Holds {@code quantity} units for an open reservation.
     *
     * @throws IllegalStateException when insufficient availability; the caller
     *         translates this into a domain failure, not a 500
     */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (!canReserve(quantity)) {
            throw new IllegalStateException(
                    "Insufficient availability for SKU " + sku + ": requested=" + quantity
                            + " available=" + available());
        }
        this.reserved += quantity;
        touch();
    }

    /** Returns an unconfirmed hold to sellable stock. Idempotent at the caller. */
    public void release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Release quantity must be positive");
        }
        // Clamp rather than throw: a double-release must not corrupt the ledger.
        this.reserved = Math.max(0, this.reserved - quantity);
        touch();
    }

    /**
     * Converts a hold into a permanent stock decrement once payment succeeds.
     * This is the point at which revenue is recognised, so it must not be undone
     * except by an explicit return/refund flow.
     */
    public void confirm(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Confirm quantity must be positive");
        }
        this.reserved = Math.max(0, this.reserved - quantity);
        this.onHand = Math.max(0, this.onHand - quantity);
        touch();
    }

    /**
     * Undoes a {@link #confirm}, returning the units to on-hand stock.
     *
     * <p>The compensating move for a checkout that confirmed stock and then
     * failed to persist the order. Without it the decrement {@code confirm} applied
     * is permanent, because {@link #release} refuses a reservation that is no
     * longer pending and the sweeper ignores anything that is not pending - so
     * stock disappears for an order that will never exist.
     *
     * <p>Only {@code onHand} moves. {@code confirm} already decremented
     * {@code reserved}, so restoring it here would inflate the hold counter and
     * make stock look permanently unavailable rather than sellable. After this,
     * {@code available()} is back to what it was before the reservation was ever
     * taken, which is the correct resting state for an order that was abandoned.
     *
     * <p>Not clamped, unlike {@link #release} and {@link #confirm}. Those clamp
     * because they decrement a counter that a repeated call could drive negative.
     * This one increments, so a repeated call would inflate {@code onHand} - and
     * that is prevented one level up, by {@code InventoryReservation.unconfirm()}
     * refusing to transition a reservation that is not CONFIRMED.
     */
    public void unconfirm(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Unconfirm quantity must be positive");
        }
        this.onHand += quantity;
        touch();
    }

    /** Adds received stock, e.g. from a goods-in event. */
    public void receive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Received quantity must be positive");
        }
        this.onHand += quantity;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
