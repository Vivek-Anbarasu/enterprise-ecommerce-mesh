package com.enterprise.mesh.ai.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.mesh.ai.config.MeshAiProperties;
import com.enterprise.mesh.ai.domain.ProductAffinity;
import com.enterprise.mesh.ai.repository.ProductAffinityRepository;

/**
 * Personalised recommendations from behavioural affinity.
 *
 * <p>Intentionally a transparent, deterministic heuristic rather than an opaque
 * model: a merchant needs to be able to explain why a product was recommended,
 * and this logic can be inspected and regression-tested.
 *
 * <p><b>The seam is {@link #rank}, and it is cut.</b> It is a pure function of
 * {@link Candidate}s, the shopper's ordered preferences, and a popularity map, so a
 * learned model can replace the scoring without touching the API or storage. One
 * half of that seam is still unwired: nothing computes
 * {@code popularityByCategory}, so callers pass an empty map and the popularity term
 * is currently dead arithmetic. {@code mesh.ai.provider} selects nothing for the same
 * reason - see {@link MeshAiProperties}.
 *
 * <p><b>Why the read is cached.</b> {@code GET /api/ai/recommendations} is the
 * storefront's home page, so it is the single most frequent read this service
 * serves, and every call would otherwise be a full Mongo round trip plus an
 * in-memory sort of that shopper's whole affinity collection. Affinities move
 * slowly - a shopper's category weights are barely different between two
 * consecutive page loads - so the result is a good cache candidate for reasons
 * that have nothing to do with the data being static.
 *
 * <p><b>Why eviction, not just a TTL, carries the correctness.</b> Both mutators
 * evict the affected key on the way out ({@code beforeInvocation} stays at its
 * default {@code false}, so a failed write leaves the previous value in place
 * rather than clearing a cache entry that still matches the database). The TTL is
 * the backstop, not the primary mechanism: if eviction is ever missed, a stale
 * entry costs a shopper their recommendations for a few minutes. Without a TTL
 * at all the same bug would persist behavioural data in Redis indefinitely, which
 * for this particular service is a privacy problem and not merely a staleness one -
 * see {@code spring.cache.redis.time-to-live} in {@code application.yml}.
 *
 * <p>Cache name and key layout are tenant-first for the same reason as the rest
 * of the mesh: {@code acme:cust-123}, so two tenants can never collide on a
 * shopper id and one tenant's cached preferences are never served to another.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@CacheConfig(cacheNames = "customer-affinity")
public class RecommendationService {

    /** Below this score a shopper has no meaningful preference. */
    private static final double MIN_USEFUL_SCORE = 0.2d;

    private final ProductAffinityRepository affinityRepository;
    private final CatalogueClient catalogueClient;
    private final MeshAiProperties aiProperties;
    /**
     * Records an interaction with a category.
     *
     * <p>Every interaction shifts the shopper's weights, so the cached preference
     * list is evicted on the way out and the next storefront load recomputes it.
     * Eviction happens after the write succeeds rather than before it, so a failed
     * write leaves a cached value that still matches the database instead of
     * clearing it into a cold read.
     *
     * @param weight view=1, add-to-cart=3, purchase=8; purchase dominates because
     *               it is the strongest available intent signal
     */
    @CacheEvict(key = "#tenantId + ':' + #customerId")
    @Transactional
    public void recordInteraction(String customerId, String tenantId, String category,
                                  InteractionType interactionType) {
        if (customerId == null || category == null || category.isBlank()) {
            return;
        }
        ProductAffinity affinity = affinityRepository
                .findByCustomerIdAndTenantIdAndCategory(customerId, tenantId, category)
                .orElseGet(() -> new ProductAffinity(
                        java.util.UUID.randomUUID().toString(), customerId, tenantId, category));

        affinity.recordInteraction(interactionType.weight());
        affinityRepository.save(affinity);
        log.debug("Recorded {} interaction category={} customer={}",
                interactionType, category, customerId);
    }

    /**
     * Records a purchase, resolved from the bought SKUs.
     *
     * <p>Called from {@code OrderEventListener}, never from HTTP. That is the point:
     * {@code order.paid} is written by order-service in the same transaction as the
     * order, so a purchase here cannot be fabricated by a client the way a
     * {@code POST /interactions} could be.
     *
     * <p>Counts distinct categories rather than SKUs. An order of five pairs of boots
     * is one statement about one category, and weighting it five times would let a
     * single large basket dominate the profile for the whole 90-day retention window.
     *
     * <p>A SKU the catalogue cannot categorise is skipped rather than guessed at. A
     * fabricated category would be indistinguishable from a real preference afterwards,
     * and the recommendation would be quietly wrong rather than merely absent.
     *
     * @return how many categories were recorded, for the caller's log line
     */
    public int recordPurchase(String customerId, String tenantId, List<String> skus) {
        if (customerId == null || skus == null || skus.isEmpty()) {
            return 0;
        }
        Set<String> categories = new LinkedHashSet<>();
        for (String sku : skus) {
            String category = catalogueClient.categoryOf(sku, tenantId);
            if (category != null && !category.isBlank()) {
                categories.add(category);
            }
        }
        categories.forEach(category ->
                recordInteraction(customerId, tenantId, category, InteractionType.PURCHASE));
        if (categories.size() < skus.size()) {
            log.warn("Purchase recorded for {}/{} skus; catalogue could not categorise the rest "
                    + "customer={} tenant={}", categories.size(), skus.size(), customerId, tenantId);
        }
        return categories.size();
    }

    /**
     * Returns the shopper's preferred categories, strongest first.
     *
     * <p>Cached because this is the storefront home page. The key is tenant-first
     * so the same shopper id in two tenants is two entries rather than one.
     *
     * <p>Empty results are cached deliberately. A shopper with no history is the
     * common case, not the exception, and caching the empty list is precisely what
     * stops every new shopper's first page load from becoming a Mongo round trip.
     * It is safe because both mutators evict the same key, so the moment they do
     * have a preference the next read recomputes it.
     *
     * <p>Note the interaction with {@code mesh.ai.max-recommendations}: the limit is
     * applied while computing, so a cached entry keeps whatever ceiling was in force
     * when it was written. Lowering the property therefore does not take effect on
     * entries already in Redis until the TTL expires or a mutation evicts them.
     *
     * @return empty when the shopper has no usable history; the caller then
     *         falls back to trending products rather than inventing preferences
     */
    @Cacheable(key = "#tenantId + ':' + #customerId")
    @Transactional(readOnly = true)
    public List<String> preferredCategories(String customerId, String tenantId) {
        if (customerId == null) {
            return List.of();
        }
        return affinityRepository.findByCustomerIdAndTenantId(customerId, tenantId).stream()
                .filter(affinity -> affinity.getScore() >= MIN_USEFUL_SCORE)
                .sorted(Comparator.comparingDouble(ProductAffinity::getScore).reversed())
                .limit(aiProperties.getMaxRecommendations())
                .map(ProductAffinity::getCategory)
                .toList();
    }

    /**
     * Scores candidate products against the shopper's affinity.
     *
     * <p>This is the seam a learned model replaces, so it is deliberately a pure
     * function of its arguments: no I/O, no clock, no randomness. {@link Candidate}s
     * arrive already categorised, which is why the signature changed from taking bare
     * SKUs.
     *
     * <p><b>Why candidates carry their category.</b> This method used to derive a
     * category from the SKU prefix, which {@link CatalogueClient}'s own javadoc
     * describes as "fine for a demo and wrong for a shop". It became an actual bug
     * rather than a latent one when the candidates started coming from the real
     * catalogue: a merchant filing {@code LAPTOP-7} under {@code electronics} yields
     * a derived category of {@code LAPTOP}, which is in no shopper's preference list,
     * so every candidate was silently skipped and the endpoint returned an empty shelf.
     * The category is now the catalogue's answer, which is the only authority on it.
     *
     * <p><b>Popularity is currently always zero.</b> Nothing in the mesh computes a
     * per-category popularity figure, so the caller passes an empty map and the second
     * term below contributes nothing. The scoring is therefore 100% affinity today
     * despite the formula reading 80/20. The parameter is kept because it is the
     * second half of the seam, and a source for it is the obvious next piece of work.
     */
    public List<ScoredProduct> rank(List<Candidate> candidates, List<String> preferredCategories,
                                    Map<String, Double> popularityByCategory) {
        if (preferredCategories == null || preferredCategories.isEmpty()
                || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // Absent popularity data, not an absent result. Nothing computes this map, so
        // "no figure" is the expected state rather than a caller error, and ranking on
        // affinity alone is a better answer than returning nothing.
        Map<String, Double> popularity = popularityByCategory == null ? Map.of() : popularityByCategory;

        List<ScoredProduct> scored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            int index = preferredCategories.indexOf(candidate.category());
            if (index < 0) {
                continue; // Skip products if the user has no affinity for this category
            }

            // 1. Decay by rank: the top preference outranks the fifth.
            double affinityWeight = 1.0d / (1.0d + index);

            // 2. Global trending popularity. Always 0.0 today - see the javadoc.
            double categoryPopularity = popularity.getOrDefault(candidate.category(), 0d);

            // 3. Affinity-weighted. The second term is retained for the seam but is
            //    currently multiplied by a zero map.
            scored.add(new ScoredProduct(candidate.sku(), candidate.category(),
                    round(affinityWeight * 0.8d + categoryPopularity * 0.2d)));
        }

        // Sort highest score first and limit to maximum recommendations. The SKU
        // tie-break is not cosmetic: without it, two products of equal affinity in the
        // same category would order by whichever catalogue page happened to return
        // first, so the same shopper could get a different shelf between two loads
        // that changed nothing.
        return scored.stream()
                .sorted(Comparator.<ScoredProduct>comparingDouble(ScoredProduct::score).reversed()
                        .thenComparing(ScoredProduct::sku))
                .limit(aiProperties.getMaxRecommendations())
                .toList();
    }


    /**
     * Erases a shopper's behavioural profile.
     *
     * <p>Exposed as an explicit operation because "right to be forgotten" must be
     * actionable on demand, not merely dependent on TTL expiry. The cache eviction
     * is part of that obligation rather than an optimisation: deleting the rows
     * while leaving a populated cache entry behind would keep serving behaviour
     * the shopper has asked to have erased, which is the one outcome worse than
     * the cache existing at all.
     */
    @CacheEvict(key = "#tenantId + ':' + #customerId")
    @Transactional
    public void forget(String customerId, String tenantId) {
        affinityRepository.deleteByCustomerIdAndTenantId(customerId, tenantId);
        log.info("Erased behavioural profile for customer {}", customerId);
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

    /**
     * A catalogue product offered to {@link #rank}, already carrying the category the
     * catalogue filed it under.
     *
     * <p>Supplied by {@link CatalogueClient#candidates}, which reads the category off
     * the browse response rather than deriving one.
     */
    public record Candidate(String sku, String category) {
    }
}