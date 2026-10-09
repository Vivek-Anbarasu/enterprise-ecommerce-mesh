package com.enterprise.mesh.shipping.domain;

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
 * A shipment for one order.
 *
 * <p>State transitions are monotonic: a carrier cannot un-deliver a parcel, so
 * moving backwards is rejected. {@code @Version} makes concurrent carrier
 * webhooks safe - the duplicate update loses and is simply re-read.
 */
@Entity
@Table(name = "shipments", indexes = {
        @Index(name = "idx_shipment_order", columnList = "order_id"),
        @Index(name = "idx_shipment_tracking", columnList = "tracking_number"),
        @Index(name = "idx_shipment_tenant_status", columnList = "tenant_id,status")
})
public class Shipment {

    public enum ShipmentStatus {
        /** Order accepted, label not yet purchased. */
        PENDING,
        /** Label created and handed to the carrier. */
        LABEL_CREATED,
        /** In transit. */
        IN_TRANSIT,
        OUT_FOR_DELIVERY,
        DELIVERED,
        /** Delivery attempted unsuccessfully. */
        FAILED_ATTEMPT,
        RETURNED,
        CANCELLED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ShipmentStatus status;

    @Column(name = "carrier", length = 64)
    private String carrier;

    @Column(name = "service_level", length = 64)
    private String serviceLevel;

    @Column(name = "tracking_number", length = 128)
    private String trackingNumber;

    @Column(name = "label_url", length = 512)
    private String labelUrl;

    @Column(name = "shipping_address", nullable = false, length = 1024)
    private String shippingAddress;

    @Column(name = "estimated_delivery", length = 64)
    private String estimatedDelivery;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Shipment() {
        // required by JPA
    }

    public Shipment(String id, String orderId, String tenantId, String customerId,
                    String shippingAddress, String serviceLevel) {
        this.id = id;
        this.orderId = orderId;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.shippingAddress = shippingAddress;
        this.serviceLevel = serviceLevel;
        this.status = ShipmentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void createLabel(String carrier, String trackingNumber, String labelUrl,
                            String estimatedDelivery) {
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.labelUrl = labelUrl;
        this.estimatedDelivery = estimatedDelivery;
        advanceTo(ShipmentStatus.LABEL_CREATED);
    }

    /**
     * Applies a carrier-reported status.
     *
     * <p>Ignores attempts to move backwards, which carriers do occasionally
     * report while their own systems settle.
     */
    public void updateStatus(ShipmentStatus newStatus) {
        if (newStatus == this.status) {
            return;
        }
        if (!isForwardProgress(this.status, newStatus)) {
            throw new IllegalStateException(
                    "Non-monotonic shipment transition " + this.status + " -> " + newStatus);
        }
        this.status = newStatus;
        if (newStatus == ShipmentStatus.DELIVERED) {
            this.deliveredAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    private void advanceTo(ShipmentStatus target) {
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /** Terminal states accept no further transitions. */
    private static boolean isForwardProgress(ShipmentStatus from, ShipmentStatus to) {
        return switch (from) {
            case PENDING -> to == ShipmentStatus.LABEL_CREATED || to == ShipmentStatus.CANCELLED;
            case LABEL_CREATED -> to == ShipmentStatus.IN_TRANSIT
                                  || to == ShipmentStatus.FAILED_ATTEMPT
                                  || to == ShipmentStatus.RETURNED
                                  || to == ShipmentStatus.CANCELLED;
            case IN_TRANSIT -> to == ShipmentStatus.OUT_FOR_DELIVERY
                               || to == ShipmentStatus.DELIVERED
                               || to == ShipmentStatus.FAILED_ATTEMPT
                               || to == ShipmentStatus.RETURNED;
            case OUT_FOR_DELIVERY -> to == ShipmentStatus.DELIVERED
                                    || to == ShipmentStatus.FAILED_ATTEMPT;
            case FAILED_ATTEMPT -> to == ShipmentStatus.IN_TRANSIT
                                  || to == ShipmentStatus.OUT_FOR_DELIVERY
                                  || to == ShipmentStatus.DELIVERED
                                  || to == ShipmentStatus.RETURNED;
            case DELIVERED, RETURNED, CANCELLED -> false;
        };
    }

    public String getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getServiceLevel() {
        return serviceLevel;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public String getLabelUrl() {
        return labelUrl;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public String getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
