package com.enterprise.mesh.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One payment attempt against a provider.
 *
 * <p>The {@code idempotencyKey} is the load-bearing field: a unique index on it
 * is what makes a retried authorisation return the original result rather than
 * creating a second charge. Every write path must therefore treat it as
 * immutable once set.
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id"),
        @Index(name = "idx_payment_tenant_order", columnList = "tenant_id,order_id")
})
public class Payment {

    public enum PaymentStatus {
        /** Funds reserved with the provider but not yet taken. */
        AUTHORISED,
        /** Funds taken; revenue recognised. */
        CAPTURED,
        /** Authorisation released without taking funds. */
        VOIDED,
        /** Captured funds returned to the customer. */
        REFUNDED,
        /** Provider rejected the attempt. */
        DECLINED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    /** Caller-supplied; unique per tenant. Drives idempotent replay. */
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    /** Provider-side reference. Never a card number. */
    @Column(name = "provider_reference", length = 128)
    private String providerReference;

    /** Masked instrument label for support, e.g. "Visa ****4242". */
    @Column(name = "instrument_label", length = 64)
    private String instrumentLabel;

    @Column(name = "decline_reason", length = 256)
    private String declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // required by JPA
    }

    public Payment(String id, String idempotencyKey, String tenantId, String orderId,
                   BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.DECLINED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void authorise(String providerReference, String instrumentLabel) {
        requireStatus(PaymentStatus.DECLINED, "authorise");
        this.providerReference = providerReference;
        this.instrumentLabel = instrumentLabel;
        this.status = PaymentStatus.AUTHORISED;
        this.updatedAt = Instant.now();
    }

    public void capture() {
        requireStatus(PaymentStatus.AUTHORISED, "capture");
        this.status = PaymentStatus.CAPTURED;
        this.updatedAt = Instant.now();
    }

    public void voidAuthorisation() {
        requireStatus(PaymentStatus.AUTHORISED, "void");
        this.status = PaymentStatus.VOIDED;
        this.updatedAt = Instant.now();
    }

    public void refund() {
        requireStatus(PaymentStatus.CAPTURED, "refund");
        this.status = PaymentStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }

    public void decline(String reason) {
        requireStatus(PaymentStatus.DECLINED, "decline");
        this.declineReason = reason;
        this.status = PaymentStatus.DECLINED;
        this.updatedAt = Instant.now();
    }

    /** Rejects transitions that would double-capture or refund un-captured funds. */
    private void requireStatus(PaymentStatus expected, String operation) {
        if (this.status != expected) {
            throw new IllegalStateException(
                    "Cannot " + operation + " a payment in state " + this.status + " (" + id + ")");
        }
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

    public String getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getInstrumentLabel() {
        return instrumentLabel;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
