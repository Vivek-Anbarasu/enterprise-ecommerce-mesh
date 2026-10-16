package com.enterprise.mesh.order.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;

/**
 * A priced line on an order.
 *
 * <p>Unit price and line total are snapshots taken at checkout. Re-deriving them
 * from the catalogue later would silently change what a customer agreed to pay
 * whenever a price is updated.
 *
 * <p><b>Read-only, deliberately.</b> {@code @Getter} only, no {@code @Setter}:
 * the two money fields are the checkout snapshot the class exists to preserve, so
 * they must be immutable for the lifetime of the line. Nothing may move them
 * without also re-running {@code Order.recalculateTotals}, which reads
 * {@code getLineTotal()} from every line - a line whose total changed on its own
 * would leave the order header describing a set of lines it no longer has.
 *
 * <p>{@code order} is the owning aggregate, fixed at construction. It is also
 * what makes this an aggregate child rather than a standalone entity: lines are
 * reached through {@code Order.getLines()}, and are created and removed with it.
 */
@Getter
@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    /** Product title at time of purchase; titles change, invoices must not. */
    @Column(name = "product_name", nullable = false, length = 256)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal;

    protected OrderLine() {
        // required by JPA
    }

    public OrderLine(Order order, String sku, String productName, int quantity,
                     BigDecimal unitPrice, BigDecimal lineTotal) {
        this.order = order;
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineTotal = lineTotal;
    }
}
