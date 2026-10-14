package com.enterprise.mesh.shipping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.shipping.domain.Shipment;
import com.enterprise.mesh.shipping.domain.Shipment.ShipmentStatus;
import com.enterprise.mesh.shipping.ShipmentRepository;

/**
 * Shipment lifecycle, driven by order events and carrier webhooks.
 *
 * <p>Creation is idempotent on {@code orderId}: Kafka redelivery of
 * {@code order.paid} must not produce a second parcel for the same order, which
 * would double the shipping cost and confuse the customer.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShippingService {

    private final ShipmentRepository shipmentRepository;
    private final CarrierGateway carrierGateway;
    /**
     * Creates (or returns) the shipment for an order and buys a label.
     *
     * @param eventId used to short-circuit a redelivered event
     */
    @Transactional
    public Shipment createShipment(String orderId, String tenantId, String customerId,
                                   String shippingAddress, String serviceLevel, String eventId) {

        var existing = shipmentRepository.findByOrderIdAndTenantId(orderId, tenantId);
        if (existing.isPresent()) {
            log.info("Shipment for order {} already exists ({}); idempotent no-op",
                    orderId, existing.get().getId());
            return existing.get();
        }

        Shipment shipment = new Shipment(UUID.randomUUID().toString(), orderId, tenantId,
                customerId, shippingAddress, serviceLevel);

        ShippingService.CarrierGateway.CarrierLabel label =
                carrierGateway.purchaseLabel(shippingAddress, serviceLevel, orderId);
        shipment.createLabel(label.carrier(), label.trackingNumber(), label.labelUrl(),
                label.estimatedDelivery());

        try {
            Shipment saved = shipmentRepository.save(shipment);
            log.info("Created shipment {} for order {} tracking={}",
                    saved.getId(), orderId, saved.getTrackingNumber());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate event won the race.
            log.info("Concurrent shipment creation for order {}; returning existing", orderId);
            return shipmentRepository.findByOrderIdAndTenantId(orderId, tenantId)
                    .orElseThrow(() -> e);
        }
    }

    /** Applies a carrier webhook status update. */
    @Transactional
    public Shipment updateStatus(String shipmentId, String tenantId, ShipmentStatus status) {
        Shipment shipment = loadOwned(shipmentId, tenantId);
        try {
            shipment.updateStatus(status);
        } catch (IllegalStateException e) {
            // Carriers occasionally report a stale status out of order. Rejecting
            // the update is correct; it must not fail the webhook and cause the
            // carrier to retry forever.
            log.warn("Ignoring non-monotonic status for shipment {}: {}", shipmentId, e.getMessage());
            return shipment;
        }
        Shipment saved = shipmentRepository.save(shipment);
        log.info("Shipment {} status now {}", shipmentId, saved.getStatus());
        return saved;
    }

    /** Cancels a shipment; only permitted before the carrier collects it. */
    @Transactional
    public Shipment cancel(String shipmentId, String tenantId) {
        Shipment shipment = loadOwned(shipmentId, tenantId);
        shipment.updateStatus(ShipmentStatus.CANCELLED);
        carrierGateway.cancelLabel(shipment.getTrackingNumber());
        log.info("Cancelled shipment {} for order {}", shipmentId, shipment.getOrderId());
        return shipmentRepository.save(shipment);
    }

    @Transactional(readOnly = true)
    public Shipment get(String shipmentId, String tenantId) {
        return loadOwned(shipmentId, tenantId);
    }

    @Transactional(readOnly = true)
    public List<Shipment> forOrder(String orderId, String tenantId) {
        return shipmentRepository.findByOrderIdAndTenantId(orderId, tenantId)
                .map(List::of)
                .orElseGet(List::of);
    }

    private Shipment loadOwned(String shipmentId, String tenantId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown shipment " + shipmentId));
        if (!shipment.getTenantId().equals(tenantId)) {
            throw new ShipmentNotFoundException(shipmentId);
        }
        return shipment;
    }

    /** Carrier integration seam. */
    public interface CarrierGateway {

        CarrierLabel purchaseLabel(String address, String serviceLevel, String orderReference);

        void cancelLabel(String trackingNumber);

        /** A purchased shipping label. */
        record CarrierLabel(String carrier, String trackingNumber, String labelUrl,
                            String estimatedDelivery) {
        }
    }

    /** Thrown when a shipment is not visible to the calling tenant. */
    public static class ShipmentNotFoundException extends RuntimeException {
        public ShipmentNotFoundException(String id) {
            super("Shipment " + id + " not found");
        }
    }
}