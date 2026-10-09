package com.enterprise.mesh.inventoryservice.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.mesh.inventoryservice.domain.InventoryReservation;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, String> {

    Optional<InventoryReservation> findByReservationIdAndTenantId(String reservationId, String tenantId);

    List<InventoryReservation> findByOrderIdAndTenantId(String orderId, String tenantId);

    /**
     * Holds that are still pending but past their expiry. These are what the
     * sweeper job releases so abandoned checkouts do not permanently reduce
     * availability.
     */
    @Query("""
            select r from InventoryReservation r
            where r.status = com.enterprise.mesh.inventoryservice.domain.InventoryReservation$ReservationStatus.PENDING
              and r.expiresAt <= :now
            order by r.expiresAt asc
            """)
    List<InventoryReservation> findExpiredPending(@Param("now") Instant now, Pageable pageable);
}
