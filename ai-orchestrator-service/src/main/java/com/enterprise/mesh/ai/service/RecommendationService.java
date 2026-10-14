package com.enterprise.mesh.ai.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.ai.domain.ProductAffinity;
import com.enterprise.mesh.ai.repository.ProductAffinityRepository;

/**
 * Personalised recommendations from behavioural affinity.
 *
 * <p>Intentionally a transparent, deterministic heuristic rather than an opaque
 * model: a merchant needs to be able to explain why a product was recommended,
 * and this logic can be inspected and regression-tested. The seam is
 * {@link #rank}, so a learned model can replace the scoring later without
 * touching the API or storage.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationService {

    /** Below this score a shopper has no meaningful preference. */
    private static final double MIN_USEFUL_SCORE = 0.2d;

    private static final int MAX_RECOMMENDATIONS = 20;

    private final ProductAffinityRepository affinityRepository;
    /**
     * Records an interaction with a category.
     *
     * @param weight view=1, add-to-cart=3, purchase=8; purchase dominates because
     *               it is the strongest available intent signal
     */
    @Transactional
    public void recordInteraction(String customerId, String tenantId, String category,
                                  InteractionType interactionType) {
        if (customerId == null || category == null || category.isBlank()) {
            return;
        }
        List<ProductAffinity> existing =
                affinityRepository.findByCustomerIdAndTenantId(customerId, tenantId);
        ProductAffinity affinity = existing.stream()
                .filter(a -> a.getCategory().equals(category))
                .findFirst()
                .orElseGet(() -> new ProductAffinity(
                        java.util.UUID.randomUUID().toString(), customerId, tenantId, category));

        affinity.recordInteraction(interactionType.weight());
        affinityRepository.save(affinity);
        log.debug("Recorded {} interaction category={} customer={}",
                interactionType, category, customerId);
    }

    /**
     * Returns the shopper's preferred categories, strongest first.
     *
     * @return empty when the shopper has no usable history; the caller then
     *         falls back to trending products rather than inventing preferences
     */
    @Transactional(readOnly = true)
    public List<String> preferredCategories(String customerId, String tenantId) {
        if (customerId == null) {
            return List.of();
        }
        return affinityRepository.findByCustomerIdAndTenantId(customerId, tenantId).stream()
                .filter(affinity -> affinity.getScore() >= MIN_USEFUL_SCORE)
                .sorted(Comparator.comparingDouble(ProductAffinity::getScore).reversed())
                .limit(MAX_RECOMMENDATIONS)
                .map(ProductAffinity::getCategory)
                .toList();
    }

    /**
     * Scores candidate products against the shopper's affinity.
     *
     * <p>Exposed so the ranking rule can be unit-tested independently of HTTP.
     */
    public List<ScoredProduct> rank(List<String> candidateSkus, List<String> preferredCategories,
                                    Map<String, Double> popularityByCategory) {
        if (preferredCategories.isEmpty()) {
            return List.of();
        }

        List<ScoredProduct> scored = new ArrayList<>();
        for (String sku : candidateSkus) {
            String category = categoryOf(sku);
            int index = preferredCategories.indexOf(category);
            if (index < 0) {
                continue;
            }
            // Decay by rank: the top preference outranks the fifth.
            double affinityWeight = 1.0d / (1.0d + index);
            double popularity = popularityByCategory.getOrDefault(category, 0d);
            scored.add(new ScoredProduct(sku, category,
                    round(affinityWeight * 0.8d + popularity * 0.2d)));
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredProduct::score).reversed())
                .limit(MAX_RECOMMENDATIONS)
                .toList();
    }

    /**
     * Erases a shopper's behavioural profile.
     *
     * <p>Exposed as an explicit operation because "right to be forgotten" must be
     * actionable on demand, not merely dependent on TTL expiry.
     */
    @Transactional
    public void forget(String customerId, String tenantId) {
        affinityRepository.deleteByCustomerIdAndTenantId(customerId, tenantId);
        log.info("Erased behavioural profile for customer {}", customerId);
    }

    private String categoryOf(String sku) {
        // In production this resolves against the catalogue; the demo
        // implementation derives a stable pseudo-category from the SKU prefix.
        int dash = sku.indexOf('-');
        return dash > 0 ? sku.substring(0, dash) : sku;
    }

    private double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    /** Relative strength of an interaction. */
    public enum InteractionType {
        VIEW(1d),
        ADD_TO_CART(3d),
        PURCHASE(8d);

        private final double weight;

        InteractionType(double weight) {
            this.weight = weight;
        }

        public double weight() {
            return weight;
        }
    }

    /** A candidate with its computed recommendation score. */
    public record ScoredProduct(String sku, String category, double score) {
    }
}