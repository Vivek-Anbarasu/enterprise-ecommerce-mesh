package com.enterprise.mesh.ai.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.enterprise.mesh.ai.domain.ProductAffinity;

public interface ProductAffinityRepository extends MongoRepository<ProductAffinity, String> {

    List<ProductAffinity> findByCustomerIdAndTenantId(String customerId, String tenantId);

    void deleteByCustomerIdAndTenantId(String customerId, String tenantId);
}
