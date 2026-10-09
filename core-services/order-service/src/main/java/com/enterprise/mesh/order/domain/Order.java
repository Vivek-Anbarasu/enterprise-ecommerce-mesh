package com.enterprise.mesh.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An order aggregate: the checkout root.
 *
 * <p>Status transitions are guarded by {@link #transitionTo(OrderStatus)} so
 * that an illegal move (for example SHIPPED -> PAID) fails loudly instead of
 * corrupting the lifecycle. The aggregate is the transaction boundary; nothing
 * outside it may mutate its lines.
 */
@Entity
@Table(name = "orders")
public class Order {

    public enum OrderStatus {
        /** Created, awaiting inventory reservation. */
        PENDING,
        /** Stock held, awaiting payment authorisation. */
        RESERVED,
        /** Payment authorised, awaiting fulfilment. */
        PAID,
        /** Handed to the carrier. */
        SHIPPED,
        /** Delivered to the customer. */
        DELIVERED,
        /** Cancelled before payment; stock released. */
        CANCELLED,
        /** Paid then refunded; stock not necessarily returned. */
        REFUNDED,
        /** Cancelled after payment was captured. */
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    /** Client-supplied idempotency key; unique per tenant. */
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    /**
     * Identifier of the inventory hold. Needed to compensate (release stock) if
     * payment later fails.
     */
    @Column(name = "inventory_reservation_id", length = 64)
    private String inventoryReservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OrderStatus status;

    /**
     * Order total as captured at checkout.
     *
     * <p>Deliberately a denormalised snapshot: re-pricing a historical order
     * from today's catalogue would make invoices and refunds meaningless. Tax
     * and shipping are stored separately for the same reason.
     */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "shipping_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal shippingAmount;

    /** Opaque provider token; never a PAN. */
    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Column(name = "shipping_address", nullable = false, length = 1024)
    private String shippingAddress;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
        // required by JPA
    }

    public Order(String id, String idempotencyKey, String tenantId, String customerId,
                 String shippingAddress, String correlationId) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.shippingAddress = shippingAddress;
        this.correlationId = correlationId;
        this.status = OrderStatus.PENDING;
        this.currency = "USD";
        this.totalAmount = BigDecimal.ZERO;
        this.taxAmount = BigDecimal.ZERO;
        this.shippingAmount = BigDecimal.ZERO;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addLine(String sku, String productName, int quantity,
                        BigDecimal unitPrice, BigDecimal lineTotal) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Line quantity must be positive");
        }
        OrderLine line = new OrderLine(this, sku, productName, quantity, unitPrice, lineTotal);
        this.lines.add(line);
        recalculateTotals();
    }

    /** Recomputes the total from lines plus tax and shipping. */
    public void recalculateTotals(BigDecimal tax, BigDecimal shipping) {
        this.taxAmount = tax == null ? BigDecimal.ZERO : tax;
        this.shippingAmount = shipping == null ? BigDecimal.ZERO : shipping;
        BigDecimal subtotal = lines.stream()
                .map(OrderLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.totalAmount = subtotal.add(this.taxAmount).add(this.shippingAmount);
        this.updatedAt = Instant.now();
    }

    private void recalculateTotals() {
        BigDecimal subtotal = lines.stream()
                .map(OrderLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.totalAmount = subtotal.add(this.taxAmount).add(this.shippingAmount);
    }

    public void attachInventoryReservation(String reservationId) {
        this.inventoryReservationId = reservationId;
        this.updatedAt = Instant.now();
    }

    public void attachPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
        this.updatedAt = Instant.now();
    }

    /**
     * Moves the order to {@code target}, rejecting transitions that would
     * contradict the checkout lifecycle.
     */
    public void transitionTo(OrderStatus target) {
        if (target == this.status) {
            return;
        }
        if (!isAllowed(this.status, target)) {
            throw new IllegalStateException(
                    "Illegal order transition " + this.status + " -> " + target + " for order " + id);
        }
        this.status = target;
        if (target == OrderStatus.FAILED || target == OrderStatus.CANCELLED) {
            this.updatedAt = Instant.now();
        }
    }

    private static boolean isAllowed(OrderStatus from, OrderStatus to) {
        return switch (from) {
            case PENDING -> to == OrderStatus.RESERVED || to == OrderStatus.CANCELLED
                            || to == OrderStatus.FAILED;
            case RESERVED -> to == OrderStatus.PAID || to == OrderStatus.CANCELLED
                             || to == OrderStatus.FAILED;
            case PAID -> to == OrderStatus.SHIPPED || to == OrderStatus.REFUNDED
                         || to == OrderStatus.CANCELLED;
            case SHIPPED -> to == OrderStatus.DELIVERED;
            case DELIVERED, REFUNDED, CANCELLED, FAILED -> false;
        };
    }

    public void markFailed(String reason) {
        this.failureReason = reason == null ? "unspecified" : reason.substring(0, Math.min(511, reason.length()));
        transitionTo(OrderStatus.FAILED);
    }

    public String getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getInventoryReservationId() {
        return inventoryReservationId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getShippingAmount() {
        return shippingAmount;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
