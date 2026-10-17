package com.enterprise.mesh.ai.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.enterprise.mesh.ai.domain.ProductEmbedding;

/**
 * Vector storage for semantic search.
 *
 * <p><b>Deliberately no vector search operator.</b> {@code findNearest} and friends are MongoDB
 * Atlas Search features; this deployment is a standalone {@code mongod}, where they do not exist.
 * A full tenant scan plus a dot product is the honest implementation at this catalogue size -
 * a few thousand 768-dimension floats is a few megabytes and single-digit milliseconds, and it
 * needs no new datastore to run.
 *
 * <p>Every method takes {@code tenantId} and nothing else can reach a row, so the isolation is a
 * property of the repository surface rather than something each caller has to remember. The
 * scan returns whole rows including the vector; there is no projection that would let a
 * caller accidentally rank across tenants.
 */
public interface ProductEmbeddingRepository extends MongoRepository<ProductEmbedding, String> {

    List<ProductEmbedding> findByTenantId(String tenantId);

    java.util.Optional<ProductEmbedding> findByTenantIdAndSku(String tenantId, String sku);

    void deleteByTenantIdAndSku(String tenantId, String sku);
}