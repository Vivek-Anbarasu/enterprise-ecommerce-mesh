package com.enterprise.mesh.customer.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.enterprise.mesh.customer.domain.Customer;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByTenantIdAndSubject(String tenantId, String subject);

    Optional<Customer> findByTenantIdAndEmailIgnoreCase(String tenantId, String email);

    boolean existsByTenantIdAndEmailIgnoreCase(String tenantId, String email);

    List<Customer> findByTenantId(String tenantId);
}
