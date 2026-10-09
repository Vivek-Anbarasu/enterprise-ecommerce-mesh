package com.enterprise.mesh.shipping;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.enterprise.mesh.shipping.domain.Shipment;

public interface ShipmentRepository extends JpaRepository<Shipment, String> {

    Optional<Shipment> findByOrderIdAndTenantId(String orderId, String tenantId);

    Optional<Shipment> findByTrackingNumber(String trackingNumber);
}
