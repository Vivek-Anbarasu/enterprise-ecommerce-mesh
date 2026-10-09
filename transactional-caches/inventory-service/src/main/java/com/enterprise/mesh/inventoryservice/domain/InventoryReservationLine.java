package com.enterprise.mesh.inventoryservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * One SKU/quantity pair belonging to a reservation.
 *
 * <p>Line detail must be persisted explicitly. Storing only a total quantity on
 * the reservation row would make confirmation and release misattribute stock
 * across the SKUs of a multi-line order - for example confirming 5 units of the
 * first SKU when the order was actually 2 x A + 3 x B.
 */
@Entity
@Table(name = "inventory_reservation_lines", indexes = {
        @Index(name = "idx_res_line_reservation", columnList = "reservation_id"),
        @Index(name = "idx_res_line_sku", columnList = "sku")
})
public class InventoryReservationLine {

    @EmbeddedId
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

    public String getReservationId() {
        return id.getReservationId();
    }

    public String getSku() {
        return id.getSku();
    }

    public int getQuantity() {
        return quantity;
    }

    /** Composite key: a reservation holds a given SKU at most once. */
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

        public String getReservationId() {
            return reservationId;
        }

        public String getSku() {
            return sku;
        }

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
