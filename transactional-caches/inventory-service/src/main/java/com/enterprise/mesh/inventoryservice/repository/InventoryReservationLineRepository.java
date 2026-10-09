package com.enterprise.mesh.inventoryservice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.enterprise.mesh.inventoryservice.domain.InventoryReservationLine;

public interface InventoryReservationLineRepository
        extends JpaRepository<InventoryReservationLine, InventoryReservationLine.Key> {

    List<InventoryReservationLine> findByIdReservationId(String reservationId);
}
