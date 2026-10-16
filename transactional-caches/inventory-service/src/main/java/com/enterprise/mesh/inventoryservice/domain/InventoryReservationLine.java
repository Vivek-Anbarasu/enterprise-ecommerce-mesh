package com.enterprise.mesh.inventoryservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.Getter;

/**
 * One SKU/quantity pair belonging to a reservation.
 *
 * <p>Line detail must be persisted explicitly. Storing only a total quantity on
 * the reservation row would make confirmation and release misattribute stock
 * across the SKUs of a multi-line order - for example confirming 5 units of the
 * first SKU when the order was actually 2 x A + 3 x B.
 *
 * <p><b>Read-only.</b> {@code @Getter} on both this class and the nested
 * {@link Key}, and no {@code @Setter} anywhere: a line is part of an immutable
 * stock hold, and its {@code quantity} was validated positive at construction.
 * Editing it afterwards would move stock without the paired
 * {@code onHand}/{@code reserved} update that
 * {@code InventoryItem.confirm} performs.
 *
 * <p>The composite {@code id} opts out of generation
 * ({@code @Getter(AccessLevel.NONE)}) for two reasons. It would add a
 * {@code getId()} that is not part of this class's contract - callers ask for a
 * reservation id or a SKU, not for the key wrapper - and the two accessors that
 * <em>are</em> the contract are hand-written below, because they flatten the key
 * into its two parts rather than exposing the composite as a unit.
 */
@Getter
@Entity
@Table(name = "inventory_reservation_lines", indexes = {
        @Index(name = "idx_res_line_reservation", columnList = "reservation_id"),
        @Index(name = "idx_res_line_sku", columnList = "sku")
})
public class InventoryReservationLine {

    @EmbeddedId
    @Getter(AccessLevel.NONE)
    private Key id;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected InventoryReservationLine() {
        // required by JPA
    }

    public InventoryReservationLine(String reservationId, String sku, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Line quantity must be positive");
        }
        this.id = new Key(reservationId, sku);
        this.quantity = quantity;
    }

    /** Flattened from the composite key rather than exposing {@code getId()}. */
    public String getReservationId() {
        return id.getReservationId();
    }

    /** Flattened from the composite key rather than exposing {@code getId()}. */
    public String getSku() {
        return id.getSku();
    }

    /** Composite key: a reservation holds a given SKU at most once. */
    @Getter
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "reservation_id", nullable = false, length = 64)
        private String reservationId;

        @Column(name = "sku", nullable = false, length = 64)
        private String sku;

        protected Key() {
            // required by JPA
        }

        public Key(String reservationId, String sku) {
            this.reservationId = reservationId;
            this.sku = sku;
        }

        // equals/hashCode are hand-written deliberately: both key fields feed them,
        // and generating the pair from a subset of the fields would produce a
        // @Embeddable whose identity silently disagrees with the unique index.
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(reservationId, other.reservationId)
                    && Objects.equals(sku, other.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(reservationId, sku);
        }
    }
}
