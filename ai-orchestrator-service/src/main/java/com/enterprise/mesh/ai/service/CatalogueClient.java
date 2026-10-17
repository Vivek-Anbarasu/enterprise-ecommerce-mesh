package com.enterprise.mesh.ai.service;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.enterprise.mesh.ai.service.RecommendationService.Candidate;

/**
 * Resolves a purchased SKU to the category an affinity is recorded against.
 *
 * <p>Exists because the order event carries SKUs, not categories, and nothing in the
 * checkout path knows a category either - {@code OrderLine} stores a sku and a product
 * name, not a category. So the catalogue is the only authority on the mapping.
 *
 * <p>This replaces a placeholder. {@code RecommendationService.categoryOf} derived a
 * "category" from the SKU prefix, which is fine for a demo and wrong for a shop: a
 * merchant organising their catalogue as {@code electronics/laptops} would have every
 * laptop recorded under whatever prefix they happened to use, and the recommendation
 * would be quietly wrong for real customers.
 *
 * <p><b>Cached, because this runs once per purchased line</b> and a category changes on
 * a merchandising timescale rather than a behavioural one. The one-hour ceiling is
 * generous on purpose: it exists to collapse repeated lookups within a burst of orders,
 * not to be authoritative, and a category that has genuinely been re-filed settles
 * within the hour.
 */
@Component
@Slf4j
public class CatalogueClient {

    /**
     * Category for a SKU, or {@code null} when the catalogue cannot say.
     *
     * <p>Returns null rather than throwing on a lookup failure, and that is a
     * deliberate asymmetry with {@code RecommendationService.recordPurchase}: a
     * catalogue outage must not cost the shopper their purchase signal, because the
     * event has already been acknowledged by the time this runs. The caller skips the
     * line instead. {@code null} is not cached - a transient failure must not be
     * remembered for an hour.
     */
    @Cacheable(cacheNames = "sku-category", key = "#tenantId + ':' + #sku")
    public String categoryOf(String sku, String tenantId) {
        try {
            ProductSummary product = restClient.get()
                    // Relative, because the host is already on the builder as baseUrl.
                    // Repeating it here would both duplicate the host and make the URI
                    // depend on an Environment this class deliberately does not need.
                    .uri("/api/products/sku/{sku}", sku)
                    .header("X-Tenant-ID", tenantId)
                    .retrieve()
                    .body(ProductSummary.class);

            if (product == null || product.category() == null || product.category().isBlank()) {
                log.warn("Catalogue returned no category for sku={} tenant={}", sku, tenantId);
                return null;
            }
            return product.category();
        } catch (RestClientException e) {
            log.warn("Catalogue lookup failed sku={} tenant={}: {}", sku, tenantId, e.toString());
            return null;
        }
    }

    private final RestClient restClient;

    public CatalogueClient(RestClient.Builder builder,
                           @Value("${mesh.clients.catalog}") String baseUrl) {
        // The mesh's established pattern for service-to-service calls: a plain
        // RestClient carrying the tenant header, with no bearer token. Matches how
        // order-service reaches inventory and payment.
        this.restClient = builder.baseUrl(baseUrl).build();
        log.info("Resolving purchased SKUs against the catalogue at {}", baseUrl);
    }

    /** Only the fields needed; the catalogue's Product is far larger. */
    private record ProductSummary(String sku, String category) {
    }

    /**
     * Candidate products across a set of categories, for
     * {@link RecommendationService#rank} to score.
     *
     * <p><b>Returns the category alongside the SKU.</b> {@code GET /api/products}
     * already carries it, so this costs nothing extra - and it matters, because the
     * ranking rule must not re-derive a category from a SKU prefix. See
     * {@link RecommendationService#rank}.
     *
     * <p><b>One HTTP call per category.</b> {@code browse} takes a single
     * {@code category} parameter, so there is no batch form to ask for. The caller is
     * expected to bound how many categories it passes; see
     * {@code RecommendationController}'s category cap for why that bound exists.
     *
     * <p><b>Cached on a merchandising timescale.</b> A candidate list changes when a
     * merchant files a product, not when a shopper does, so it can safely outlive the
     * affinity entries that select from it - the same reasoning, and the same
     * service-wide TTL, as {@code sku-category}. It is not evicted by
     * {@code RecommendationService.forget}: erasing a behavioural profile has nothing
     * to do with which products exist.
     *
     * <p><b>Partial results rather than failure.</b> A category whose lookup fails is
     * logged and skipped, and the categories that did answer are still returned. The
     * same asymmetry as {@link #categoryOf}: the catalogue is a recommendation source,
     * not a dependency of the purchase path, and a blip in it must cost a shopper a
     * shorter shelf rather than an error page on the storefront's hottest read.
     */
    @Cacheable(cacheNames = "sku-candidates",
            key = "#tenantId + ':' + #categories + ':' + #perCategory")
    public List<Candidate> candidates(String tenantId, List<String> categories, int perCategory) {
        if (categories == null || categories.isEmpty()) {
            return List.of();
        }

        List<Candidate> candidates = new ArrayList<>();
        for (String category : categories) {
            if (category == null || category.isBlank()) {
                continue;
            }
            try {
                ProductPage page = restClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/api/products")
                                .queryParam("category", category)
                                .queryParam("page", 0)
                                .queryParam("size", perCategory)
                                .build())
                        .header("X-Tenant-ID", tenantId)
                        .retrieve()
                        .body(ProductPage.class);

                if (page == null || page.content() == null) {
                    log.warn("Catalogue returned no candidate page for category={} tenant={}",
                            category, tenantId);
                    continue;
                }
                for (ProductSummary product : page.content()) {
                    if (product.sku() != null && product.category() != null) {
                        candidates.add(new Candidate(product.sku(), product.category()));
                    }
                }
            } catch (RestClientException e) {
                log.warn("Candidate lookup failed category={} tenant={}: {}",
                        category, tenantId, e.toString());
            }
        }
        // Defensive copy: the list is cached and must not be mutated by a caller,
        // nor expose the mutable ArrayList to one.
        return List.copyOf(candidates);
    }

    /**
     * The catalogue's paged browse response. Only {@code content} is read; the
     * pagination fields are declared so Jackson can bind the body, and are
     * intentionally unused.
     */
    private record ProductPage(List<ProductSummary> content, long totalElements, int page, int size) {
    }
}