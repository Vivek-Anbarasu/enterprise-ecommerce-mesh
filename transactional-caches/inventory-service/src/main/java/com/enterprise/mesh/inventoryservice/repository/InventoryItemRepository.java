package com.enterprise.mesh.inventoryservice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.mesh.inventoryservice.domain.InventoryItem;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {

    Optional<InventoryItem> findBySkuAndTenantId(String sku, String tenantId);

    List<InventoryItem> findBySkuInAndTenantId(List<String> skus, String tenantId);

    boolean existsBySkuAndTenantId(String sku, String tenantId);

    /**
     * Reports SKUs whose availability dropped below the reorder threshold; used
     * by the replenishment alert and the low-stock dashboard.
     */
    @Query("""
            select i from InventoryItem i
            where i.tenantId = :tenantId
              and (i.onHand - i.reserved - i.safetyStock) <= :threshold
            order by (i.onHand - i.reserved - i.safetyStock) asc
            """)
    List<InventoryItem> findLowStock(@Param("tenantId") String tenantId,
                                     @Param("threshold") int threshold,
                                     Pageable pageable);
}
