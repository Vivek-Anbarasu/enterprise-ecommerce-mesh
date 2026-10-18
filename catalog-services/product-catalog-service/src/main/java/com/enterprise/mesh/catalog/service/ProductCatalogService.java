package com.enterprise.mesh.catalog.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.catalog.domain.Product;
import com.enterprise.mesh.catalog.messaging.ProductChangeNotice;
import com.enterprise.mesh.catalog.messaging.ProductEventPublisher;
import com.enterprise.mesh.catalog.repository.ProductRepository;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Catalogue reads and writes.
 *
 * <p>Caching strategy reflects the access pattern: product detail is read
 * constantly and changes rarely, so it is cached for a short TTL. Any write
 * evicts the affected entries rather than waiting for expiry, because a stale
 * price is a customer-trust and compliance problem, not just an annoyance.
 *
 * <p>Every write also announces itself as a {@link ProductChangeNotice}, which
 * {@link ProductEventPublisher} forwards to {@code product.events} once the transaction
 * commits. That is how {@code ai-orchestrator-service} learns to re-embed a product for
 * semantic search; nothing else in the mesh consumes the topic.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProductCatalogService {

    /**
     * Short TTL: enough to absorb browse bursts, short enough to limit staleness.
     *
     * <p>Not decorative, and deliberately not a second source of truth. This constant
     * used to be declared and never referenced while nothing configured a TTL at all,
     * so every cached product lived in Redis indefinitely and eviction was the only
     * invalidation. The effective value now lives in
     * {@code spring.cache.redis.time-to-live} in {@code application.yml}, because that
     * is the only place Spring can read a TTL from.
     *
     * <p>{@code ProductCatalogCacheConfigurationTest} asserts the running TTL equals
     * this constant, so the two cannot drift apart again: change one, change both, and
     * the build will say so if you did not.
     */
    public static final Duration PRODUCT_CACHE_TTL = Duration.ofMinutes(5);

    private final ProductRepository productRepository;

    /**
     * Announces catalogue changes in-process; {@link ProductEventPublisher} turns them into
     * Kafka records after commit. Injected so this service never touches Kafka directly and
     * the write path stays testable without a broker.
     */
    private final ApplicationEventPublisher applicationEvents;
    @Cacheable(cacheNames = "product", key = "#tenantId + ':' + #sku")
    @Transactional(readOnly = true)
    public Product findBySku(String sku, String tenantId) {
        return productRepository.findBySkuAndTenantId(sku, tenantId)
                .orElseThrow(() -> new ProductNotFoundException(sku));
    }

    @Cacheable(cacheNames = "product", key = "#tenantId + ':id:' + #id")
    @Transactional(readOnly = true)
    public Product findById(String id, String tenantId) {
        return productRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Product> browse(String category, String tenantId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize);

        return category == null || category.isBlank()
                ? productRepository.findByTenantIdAndActiveTrue(tenantId, pageable)
                : productRepository.findByCategoryAndTenantIdAndActiveTrue(category, tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public long count(String category, String tenantId) {
        return category == null || category.isBlank()
                ? productRepository.countByTenantIdAndActiveTrue(tenantId)
                : productRepository.countByCategoryAndTenantIdAndActiveTrue(category, tenantId);
    }

    @CacheEvict(cacheNames = "product", key = "#product.tenantId + ':' + #product.sku")
    @Transactional
    public Product create(Product product) {
        if (product.getCreatedAt() == null) {
            product.setCreatedAt(java.time.Instant.now());
        }
        product.setUpdatedAt(java.time.Instant.now());
        Product saved = productRepository.save(product);
        log.info("Created product sku={} tenant={}", saved.getSku(), saved.getTenantId());
        applicationEvents.publishEvent(
                new ProductChangeNotice(ProductEventPublisher.CREATED, ProductEventPublisher.of(saved)));
        return saved;
    }

    /** Full update of a product; the cache entry is dropped so prices never go stale. */
    @CacheEvict(cacheNames = "product", key = "#tenantId + ':' + #product.sku")
    @Transactional
    public Product update(String id, Product product, String tenantId) {
        Product existing = productRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ProductNotFoundException(id));
        product.setId(existing.getId());
        product.setTenantId(existing.getTenantId());
        product.setSku(existing.getSku());
        product.setCreatedAt(existing.getCreatedAt());
        product.setUpdatedAt(java.time.Instant.now());
        Product saved = productRepository.save(product);
        log.info("Updated product sku={} tenant={}", saved.getSku(), saved.getTenantId());
        applicationEvents.publishEvent(
                new ProductChangeNotice(ProductEventPublisher.UPDATED, ProductEventPublisher.of(saved)));
        return saved;
    }

    /**
     * Soft delete. Products are never hard-deleted because historical orders
     * reference their SKUs; a hard delete would break invoices and reports.
     */
    @CacheEvict(cacheNames = "product", key = "#tenantId + ':' + #sku")
    @Transactional
    public void deactivate(String sku, String tenantId) {
        Product product = productRepository.findBySkuAndTenantId(sku, tenantId)
                .orElseThrow(() -> new ProductNotFoundException(sku));
        product.setActive(false);
        product.setUpdatedAt(java.time.Instant.now());
        productRepository.save(product);
        log.info("Deactivated product sku={} tenant={}", sku, tenantId);
        // active=false travels on the event, so the consumer removes the search vector rather
        // than re-embedding a product that must no longer be findable. Leaving the vector in
        // place would keep a withdrawn product answering queries.
        applicationEvents.publishEvent(
                new ProductChangeNotice(ProductEventPublisher.DEACTIVATED, ProductEventPublisher.of(product)));
    }

    /** Raised when a SKU or id does not exist for the tenant. */
    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(String identifier) {
            super("Product " + identifier + " not found");
        }
    }
}