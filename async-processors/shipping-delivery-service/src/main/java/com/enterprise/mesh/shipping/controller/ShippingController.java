package com.enterprise.mesh.shipping.controller;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.List;

import com.enterprise.mesh.shipping.domain.Shipment;
import com.enterprise.mesh.shipping.domain.Shipment.ShipmentStatus;
import com.enterprise.mesh.shipping.service.ShippingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shipment lifecycle.
 *
 * <p>Two surfaces, deliberately on different paths:
 *
 * <ul>
 *   <li>{@code /internal/shipping/**} — reads and cancellation. Mesh-internal only.
 *   <li>{@code /api/shipping/webhooks/**} — the carrier webhook. This is a
 *       partner-facing surface entered through the api-gateway, so it lives under
 *       {@code /api}. It cannot live under {@code /internal}: the waypoint's
 *       {@code deny-internal-from-gateway} policy denies the gateway principal
 *       there, which is exactly the trust boundary that should stay closed.
 * </ul>
 */
@RestController
@Tag(name = "Shipping", description = "Shipment lifecycle")
@RequiredArgsConstructor
public class ShippingController {

    private final ShippingService shippingService;
    public record ShipmentResponse(String id, String orderId, ShipmentStatus status,
                                   String carrier, String trackingNumber, String labelUrl,
                                   String estimatedDelivery, Instant deliveredAt,
                                   Instant createdAt) {

        static ShipmentResponse from(Shipment shipment) {
            return new ShipmentResponse(shipment.getId(), shipment.getOrderId(), shipment.getStatus(),
                    shipment.getCarrier(), shipment.getTrackingNumber(), shipment.getLabelUrl(),
                    shipment.getEstimatedDelivery(), shipment.getDeliveredAt(),
                    shipment.getCreatedAt());
        }
    }

    @GetMapping("/internal/shipping/{shipmentId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Fetch a shipment")
    public ShipmentResponse get(@PathVariable String shipmentId,
                               @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ShipmentResponse.from(shippingService.get(shipmentId, tenant(tenantId)));
    }

    @GetMapping("/internal/shipping/orders/{orderId}")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN','SERVICE')")
    @Operation(summary = "Shipments for an order")
    public List<ShipmentResponse> forOrder(
            @PathVariable String orderId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return shippingService.forOrder(orderId, tenant(tenantId)).stream()
                .map(ShipmentResponse::from)
                .toList();
    }

    /**
     * Carrier status webhook.
     *
     * <p>Restricted to the carrier service account. The path is under {@code /api}
     * because it enters through the api-gateway and {@code /internal/*} is denied to
     * the gateway principal at the waypoint. The gateway applies a circuit breaker,
     * bulkhead and timeout to this route, but deliberately <b>no retry</b>: a status
     * transition is not safe to replay.
     */
    @PostMapping("/api/shipping/webhooks/{shipmentId}/status")
    @PreAuthorize("hasAnyRole('CARRIER','ADMIN')")
    @Operation(summary = "Apply a carrier-reported status")
    public ResponseEntity<ShipmentResponse> updateStatus(
            @PathVariable String shipmentId,
            @RequestParam ShipmentStatus status,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(ShipmentResponse.from(
                shippingService.updateStatus(shipmentId, tenant(tenantId), status)));
    }

    @PostMapping("/internal/shipping/{shipmentId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
    @Operation(summary = "Cancel a shipment before carrier collection")
    public ResponseEntity<ShipmentResponse> cancel(
            @PathVariable String shipmentId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ResponseEntity.ok(ShipmentResponse.from(
                shippingService.cancel(shipmentId, tenant(tenantId))));
    }

    private String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "public" : tenantId;
    }
}