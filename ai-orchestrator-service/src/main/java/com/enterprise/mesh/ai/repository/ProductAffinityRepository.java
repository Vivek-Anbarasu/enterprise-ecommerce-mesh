package com.enterprise.mesh.ai.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.enterprise.mesh.ai.domain.ProductAffinity;

public interface ProductAffinityRepository extends MongoRepository<ProductAffinity, String> {

    List<ProductAffinity> findByCustomerIdAndTenantId(String customerId, String tenantId);

    /**
     * The single-affinity lookup, used on every write path.
     *
     * <p>{@link #findByCustomerIdAndTenantId} loads every category the shopper has
     * touched and filters by category in memory, so the {@code {customerId, category}}
     * compound index cannot be used - the query never mentions category. On the
     * write path, which runs once per view beacon and once per purchased line, that
     * is the difference between one indexed lookup and a scan of the shopper's whole
     * affinity collection.
     *
     * <p>Tenant is in the predicate rather than implied, so a shopper id reused
     * across two tenants cannot resolve to the other tenant's profile.
     */
    Optional<ProductAffinity> findByCustomerIdAndTenantIdAndCategory(
            String customerId, String tenantId, String category);

    void deleteByCustomerIdAndTenantId(String customerId, String tenantId);
}