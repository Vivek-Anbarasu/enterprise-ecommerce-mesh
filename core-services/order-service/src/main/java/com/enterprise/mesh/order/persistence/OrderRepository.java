package com.enterprise.mesh.order.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.enterprise.mesh.order.domain.Order;

public interface OrderRepository extends JpaRepository<Order, String> {

    /**
     * Idempotency lookup. A retried checkout with the same key must return the
     * original order rather than creating a second one (and charging twice).
     */
    Optional<Order> findByIdempotencyKeyAndTenantId(String idempotencyKey, String tenantId);

    @Query("""
            select o from Order o
            where o.tenantId = :tenantId
              and (:customerId is null or o.customerId = :customerId)
              and (:status is null or o.status = :status)
            """)
    Page<Order> search(@Param("tenantId") String tenantId,
                       @Param("customerId") String customerId,
                       @Param("status") com.enterprise.mesh.order.domain.Order.OrderStatus status,
                       Pageable pageable);

    List<Order> findByInventoryReservationId(String inventoryReservationId);
}
