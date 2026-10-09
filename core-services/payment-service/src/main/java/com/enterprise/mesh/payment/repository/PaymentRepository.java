package com.enterprise.mesh.payment.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.enterprise.mesh.payment.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByIdempotencyKeyAndTenantId(String idempotencyKey, String tenantId);

    List<Payment> findByOrderIdAndTenantIdOrderByCreatedAtDesc(String orderId, String tenantId);
}
