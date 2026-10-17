package com.enterprise.mesh.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.enterprise.mesh.ai.config.MeshAiProperties;
import com.enterprise.mesh.ai.service.RecommendationService.Candidate;
import com.enterprise.mesh.ai.service.RecommendationService.ScoredProduct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The ranking rule.
 *
 * <p>This method had no caller and therefore no test, which is how it survived with a
 * category derived from the SKU prefix. The candidates now come from the real
 * catalogue, so that shortcut stopped being cosmetic and started dropping every
 * product on the floor - see {@code categoryIsNotDerivedFromTheSkuPrefix}, which is the
 * regression test for it.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceRankTest {

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(null, null, propertiesWithCeiling(20));
    }

    private static MeshAiProperties propertiesWithCeiling(int ceiling) {
        MeshAiProperties properties = new MeshAiProperties();
        properties.setMaxRecommendations(ceiling);
        return properties;
    }

    private static Candidate candidate(String sku, String category) {
        return new Candidate(sku, category);
    }

    @Test
    @DisplayName("the strongest preference outranks a weaker one, at 1/(1+index)")
    void strongestPreferenceScoresHighest() {
        List<ScoredProduct> ranked = service.rank(
                List.of(candidate("B-1", "boots"), candidate("C-1", "coats")),
                List.of("boots", "coats"),
                Map.of());

        // boots is index 0 -> weight 1.0 -> 0.8; coats is index 1 -> 0.5 -> 0.4.
        assertThat(ranked).extracting(ScoredProduct::sku).containsExactly("B-1", "C-1");
        assertThat(ranked.get(0).score()).isEqualTo(0.8d);
        assertThat(ranked.get(1).score()).isEqualTo(0.4d);
    }

    @Test
    @DisplayName("the category comes from the catalogue, never from the SKU prefix")
    void categoryIsNotDerivedFromTheSkuPrefix() {
        // The regression. This method used to read the category off the SKU prefix, so
        // "LAPTOP-7" became category "LAPTOP", matched nothing, and the shopper was
        // shown an empty shelf. With candidates coming from the real catalogue the
        // merchant's filing wins.
        List<ScoredProduct> ranked = service.rank(
                List.of(candidate("LAPTOP-7", "electronics")),
                List.of("electronics"),
                Map.of());

        assertThat(ranked)
                .as("A SKU whose prefix is not its category must still be ranked")
                .singleElement()
                .satisfies(product -> {
                    assertThat(product.sku()).isEqualTo("LAPTOP-7");
                    assertThat(product.category()).isEqualTo("electronics");
                    assertThat(product.score()).isEqualTo(0.8d);
                });
    }

    @Test
    @DisplayName("a product in a category the shopper has no affinity for is skipped, not scored zero")
    void unaffiliatedCategoriesAreSkipped() {
        List<ScoredProduct> ranked = service.rank(
                List.of(candidate("B-1", "boots"), candidate("K-1", "kitchen")),
                List.of("boots"),
                Map.of());

        // Scoring it zero and returning it would put a product the shopper has no
        // history with in their recommendations, below everything they do like.
        assertThat(ranked).extracting(ScoredProduct::sku).containsExactly("B-1");
    }

    @Test
    @DisplayName("an empty popularity map leaves scoring purely affinity-weighted")
    void popularityIsInertWithoutASource() {
        // Nothing in the mesh computes popularity, so the caller passes an empty map.
        // The 0.2 multiplier is then multiplying zero, and the result must be exactly
        // the affinity term - not a value that looks like it blends two signals.
        List<ScoredProduct> ranked = service.rank(
                List.of(candidate("B-1", "boots")),
                List.of("boots"),
                Map.of());

        assertThat(ranked.get(0).score())
                .as("0.8 means the popularity term contributed nothing")
                .isEqualTo(0.8d);
    }

    @Test
    @DisplayName("a supplied popularity figure does contribute, so the seam is real")
    void popularityContributesWhenSupplied() {
        // The companion to the test above: it proves the parameter is wired rather than
        // vestigial, which is what makes it safe to describe as a seam.
        List<ScoredProduct> ranked = service.rank(
                List.of(candidate("B-1", "boots")),
                List.of("boots"),
                Map.of("boots", 1.0d));

        // 0.8 affinity + 0.2 * 1.0 popularity.
        assertThat(ranked.get(0).score()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("ties break by SKU, so the same shopper sees a stable shelf")
    void tiesBreakBySku() {
        // Two products in the shopper's top category score identically. Without the
        // tie-break their order follows catalogue pagination order, which can change
        // between two page loads that changed nothing - the recommendations reshuffle
        // for no reason a shopper can see.
        List<ScoredProduct> ranked = service.rank(
                List.of(candidate("Z-9", "boots"), candidate("A-1", "boots")),
                List.of("boots"),
                Map.of());

        assertThat(ranked).extracting(ScoredProduct::sku).containsExactly("A-1", "Z-9");
    }

    @Test
    @DisplayName("mesh.ai.max-recommendations caps the output")
    void respectsTheConfiguredCeiling() {
        RecommendationService capped = new RecommendationService(null, null, propertiesWithCeiling(2));

        List<ScoredProduct> ranked = capped.rank(
                List.of(candidate("A", "boots"), candidate("B", "boots"), candidate("C", "boots")),
                List.of("boots"),
                Map.of());

        assertThat(ranked).hasSize(2);
    }

    @Test
    @DisplayName("nothing in, nothing out")
    void emptyInputRanksNothing() {
        assertThat(service.rank(List.of(), List.of("boots"), Map.of())).isEmpty();
        assertThat(service.rank(List.of(candidate("B-1", "boots")), List.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("missing arguments are tolerated rather than throwing on the home page")
    void missingInputIsTolerated() {
        // A null here would be a 500 on the storefront's hottest read rather than an
        // empty shelf. Null candidates or preferences mean there is genuinely nothing
        // to rank; a null popularity map means only that the figure is unavailable,
        // which still leaves affinity to rank on.
        assertThat(service.rank(null, List.of("boots"), Map.of())).isEmpty();
        assertThat(service.rank(List.of(), List.of("boots"), Map.of())).isEmpty();
        assertThat(service.rank(List.of(candidate("B-1", "boots")), null, Map.of())).isEmpty();
        assertThat(service.rank(List.of(candidate("B-1", "boots")), List.of(), Map.of())).isEmpty();

        assertThat(service.rank(List.of(candidate("B-1", "boots")), List.of("boots"), null))
                .as("Absent popularity must degrade the score, not suppress the recommendation")
                .singleElement()
                .extracting(ScoredProduct::score)
                .isEqualTo(0.8d);
    }
}