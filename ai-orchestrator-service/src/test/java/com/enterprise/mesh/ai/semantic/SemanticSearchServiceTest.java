package com.enterprise.mesh.ai.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import com.enterprise.mesh.ai.domain.ProductEmbedding;
import com.enterprise.mesh.ai.repository.ProductEmbeddingRepository;
import com.enterprise.mesh.ai.semantic.SemanticSearchService.SemanticHit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Brute-force search behaviour, with the embedder stubbed.
 *
 * <p>Stubs rather than a real model: the vector maths is covered by {@link VectorsTest}, and
 * what needs proving here is the contract around it - tenant scoping, degradation when the model
 * is absent, not silently mixing incompatible vector spaces, and refusing to report "nothing
 * matched" when the honest answer is "nothing was searched".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SemanticSearchServiceTest {

    @Mock
    private ProductEmbeddingRepository embeddings;

    @Mock
    private TextEmbedder embedder;

    private SemanticProperties properties;
    private SemanticSearchService service;

    @BeforeEach
    void setUp() {
        properties = new SemanticProperties();
        service = new SemanticSearchService(embeddings, embedder, properties);
    }

    private void embedderAvailable() {
        given(embedder.isAvailable()).willReturn(true);
        // Query vector points along +x, so similarity is just the x component of each product.
        given(embedder.embedQuery(any())).willReturn(new float[]{1f, 0f});
    }

    /**
     * A stored vector whose cosine similarity against the +x query axis is exactly {@code score}.
     *
     * <p>Built at the matching angle rather than written as raw components on purpose. A vector
     * of {@code (0.9, 0)} against a query of {@code (1, 0)} scores 1.0, not 0.9 - cosine ignores
     * magnitude, and every such vector is parallel to the query. The first draft of these tests
     * made that mistake and every product tied at 1.0, which is exactly the kind of fixture bug
     * that makes an ordering test pass for the wrong reason.
     */
    private ProductEmbedding embedding(String tenant, String sku, double score) {
        double perpendicular = Math.sqrt(1d - score * score);
        ProductEmbedding embedding = new ProductEmbedding();
        embedding.setTenantId(tenant);
        embedding.setSku(sku);
        embedding.setVector(List.of(score, perpendicular));
        embedding.setModel(properties.getModelId());
        return embedding;
    }

    @Test
    @DisplayName("results are ordered by similarity, strongest first")
    void ordersByScore() {
        embedderAvailable();
        given(embeddings.findByTenantId("acme")).willReturn(List.of(
                embedding("acme", "WEAK", 0.3d),
                embedding("acme", "STRONG", 0.9d),
                embedding("acme", "MIDDLE", 0.5d)));

        assertThat(service.search("acme", "waterproof jacket"))
                .extracting(SemanticHit::sku)
                .containsExactly("STRONG", "MIDDLE", "WEAK");
    }

    @Test
    @DisplayName("only the requesting tenant's vectors are read")
    void scopedToOneTenant() {
        embedderAvailable();
        given(embeddings.findByTenantId("acme")).willReturn(List.of(embedding("acme", "A", 0.9d)));
        given(embeddings.findByTenantId("globex")).willReturn(List.of(embedding("globex", "B", 0.9d)));

        assertThat(service.search("globex", "boots")).extracting(SemanticHit::sku).containsExactly("B");

        // The repository takes the tenant as an argument and has no unscoped finder, so a
        // cross-tenant read is not reachable by forgetting an argument.
        verify(embeddings).findByTenantId("globex");
        verify(embeddings, never()).findByTenantId("acme");
    }

    @Test
    @DisplayName("mesh.ai.semantic.top-k caps the result set")
    void respectsTopK() {
        embedderAvailable();
        properties.setTopK(2);
        given(embeddings.findByTenantId("acme")).willReturn(List.of(
                embedding("acme", "A", 0.9d),
                embedding("acme", "B", 0.8d),
                embedding("acme", "C", 0.7d)));

        assertThat(service.search("acme", "boots")).hasSize(2);
    }

    @Test
    @DisplayName("near-zero similarities are dropped rather than ranked")
    void dropsTheTail() {
        // Cosine between arbitrary product text and a shopper's query is frequently near zero.
        // Returning everything in score order would be a confident ranking of noise.
        embedderAvailable();
        given(embeddings.findByTenantId("acme")).willReturn(List.of(
                embedding("acme", "MATCH", 0.8d),
                embedding("acme", "NOISE-1", 0.05d),
                embedding("acme", "NOISE-2", 0.01d)));

        assertThat(service.search("acme", "waterproof jacket"))
                .extracting(SemanticHit::sku)
                .containsExactly("MATCH");
    }

    @Test
    @DisplayName("ties break by SKU, so the same query returns the same order")
    void tiesBreakBySku() {
        embedderAvailable();
        given(embeddings.findByTenantId("acme")).willReturn(List.of(
                embedding("acme", "Z-9", 0.8d),
                embedding("acme", "A-1", 0.8d)));

        assertThat(service.search("acme", "boots"))
                .extracting(SemanticHit::sku)
                .containsExactly("A-1", "Z-9");
    }

    @Test
    @DisplayName("vectors from a different model are skipped, not scored")
    void foreignModelVectorsAreSkipped() {
        // Cosine similarity between two unrelated vector spaces still returns a confident
        // number. Comparing across models produces plausible, wrong results.
        embedderAvailable();
        ProductEmbedding stale = embedding("acme", "OLD-MODEL", 0.99d);
        stale.setModel("some-other-encoder");
        given(embeddings.findByTenantId("acme")).willReturn(List.of(
                stale, embedding("acme", "CURRENT", 0.8d)));

        assertThat(service.search("acme", "boots"))
                .extracting(SemanticHit::sku)
                .containsExactly("CURRENT");
    }

    @Test
    @DisplayName("a vector of the wrong width is skipped, not scored")
    void mismatchedWidthIsSkipped() {
        embedderAvailable();
        ProductEmbedding wrongWidth = new ProductEmbedding();
        wrongWidth.setTenantId("acme");
        wrongWidth.setSku("OLD-WIDTH");
        wrongWidth.setModel(properties.getModelId());
        wrongWidth.setVector(List.of(1d, 0d, 0d, 0d));
        given(embeddings.findByTenantId("acme")).willReturn(List.of(
                wrongWidth, embedding("acme", "CURRENT", 0.8d)));

        assertThat(service.search("acme", "boots"))
                .extracting(SemanticHit::sku)
                .containsExactly("CURRENT");
    }

    @Test
    @DisplayName("an unavailable model reports unavailable, never an empty result set")
    void unavailableModelIsNotAnEmptyResult() {
        // The distinction this protects: "search is not deployed" and "nothing matched" are
        // different answers, and collapsing them makes a missing model look like a working
        // search that found nothing.
        given(embedder.isAvailable()).willReturn(false);

        assertThatThrownBy(() -> service.search("acme", "boots"))
                .isInstanceOf(SemanticSearchUnavailableException.class);

        verify(embeddings, never()).findByTenantId(any());
    }

    @Test
    @DisplayName("a blank query is rejected rather than searched")
    void blankQueryIsRejected() {
        embedderAvailable();

        assertThatThrownBy(() -> service.search("acme", "   "))
                .isInstanceOf(IllegalArgumentException.class);

        verify(embedder, never()).embedQuery(any());
    }

    @Test
    @DisplayName("a missing tenant is rejected")
    void missingTenantIsRejected() {
        embedderAvailable();

        assertThatThrownBy(() -> service.search(null, "boots"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an unindexed tenant returns nothing without failing")
    void unindexedTenantIsEmpty() {
        embedderAvailable();
        given(embeddings.findByTenantId("new-tenant")).willReturn(List.of());

        assertThat(service.search("new-tenant", "boots")).isEmpty();
    }
}