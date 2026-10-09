package com.enterprise.mesh.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import org.springframework.stereotype.Repository;

import com.enterprise.mesh.catalog.domain.Product;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {

    Optional<Product> findBySkuAndTenantId(String sku, String tenantId);

    Optional<Product> findByIdAndTenantId(String id, String tenantId);

    List<Product> findByCategoryAndTenantIdAndActiveTrue(String category, String tenantId,
                                                         Pageable pageable);

    /**
     * Paged, tenant-scoped listing. Pageable is required: an unbounded catalogue
     * scan is the fastest way to take this service down in production.
     */
    List<Product> findByTenantIdAndActiveTrue(String tenantId, Pageable pageable);

    long countByTenantIdAndActiveTrue(String tenantId);

    long countByCategoryAndTenantIdAndActiveTrue(String category, String tenantId);
}
