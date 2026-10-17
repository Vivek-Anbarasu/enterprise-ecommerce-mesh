package com.enterprise.mesh.ai.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import com.enterprise.mesh.ai.domain.ProductEmbedding;
import com.enterprise.mesh.ai.repository.ProductEmbeddingRepository;
import com.enterprise.mesh.ai.semantic.SemanticIndexService.IndexableProduct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Indexing behaviour, with the embedder stubbed.
 *
 * <p>The fingerprint is the thing worth protecting here. Inference on CPU is the slowest thing
 * in the service by orders of magnitude, so a reindex that re-embeds an unchanged product is not
 * a small inefficiency - it is the difference between a reindex that finishes and one that
 * times out.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SemanticIndexServiceTest {

    @Mock
    private ProductEmbeddingRepository embeddings;

    @Mock
    private TextEmbedder embedder;

    private SemanticProperties properties;
    private SemanticIndexService service;

    @BeforeEach
    void setUp() {
        properties = new SemanticProperties();
        given(embedder.isAvailable()).willReturn(true);
        given(embedder.embedDocument(any())).willReturn(new float[]{0.6f, 0.8f});
        service = new SemanticIndexService(embeddings, embedder, properties);
    }

    private ProductEmbedding existing(String fingerprint, String prefix) {
        ProductEmbedding stored = new ProductEmbedding();
        stored.setId("id-1");
        stored.setTenantId("acme");
        stored.setSku("BOOTS-1");
        stored.setModel(properties.getModelId());
        stored.setTextPrefix(prefix);
        stored.setTextFingerprint(fingerprint);
        return stored;
    }

    @Test
    @DisplayName("a new product is embedded and stored")
    void indexesANewProduct() {
        given(embeddings.findByTenantIdAndSku("acme", "BOOTS-1")).willReturn(Optional.empty());

        assertThat(service.index("acme", "BOOTS-1", "Waterproof hiking boots")).isTrue();

        ArgumentCaptor<ProductEmbedding> saved = ArgumentCaptor.forClass(ProductEmbedding.class);
        verify(embeddings).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo("acme");
        assertThat(saved.getValue().getSku()).isEqualTo("BOOTS-1");
        assertThat(saved.getValue().getModel()).isEqualTo("nomic-embed-text");
        // Offset, not equality: the model returns float[] and Mongo stores Double, so the
        // values are widened. (double) 0.6f is 0.6000000238418579, and an exact comparison
        // here would be asserting float representation rather than behaviour. Widening then
        // narrowing back to float is lossless, so the round trip itself is exact.
        assertThat(saved.getValue().getVector()).hasSize(2);
        assertThat(saved.getValue().getVector().get(0))
                .isCloseTo(0.6d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(saved.getValue().getVector().get(1))
                .isCloseTo(0.8d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(saved.getValue().getTextPrefix()).isEqualTo("search_document: ");
        assertThat(saved.getValue().getTextFingerprint()).isNotBlank();
    }

    @Test
    @DisplayName("an unchanged product is not re-embedded")
    void skipsUnchangedProducts() {
        // Index once, then reindex. The second pass must cost no inference at all.
        service.index("acme", "BOOTS-1", "Waterproof hiking boots");

        ArgumentCaptor<ProductEmbedding> stored = ArgumentCaptor.forClass(ProductEmbedding.class);
        verify(embeddings).save(stored.capture());
        given(embeddings.findByTenantIdAndSku("acme", "BOOTS-1"))
                .willReturn(Optional.of(stored.getValue()));

        assertThat(service.index("acme", "BOOTS-1", "Waterproof hiking boots"))
                .as("Re-running the model over an unchanged description is the cost this skips")
                .isFalse();

        verify(embedder, org.mockito.Mockito.times(1)).embedDocument(any());
    }

    @Test
    @DisplayName("an edited description is re-embedded")
    void reindexesChangedProducts() {
        service.index("acme", "BOOTS-1", "Waterproof hiking boots");

        ArgumentCaptor<ProductEmbedding> stored = ArgumentCaptor.forClass(ProductEmbedding.class);
        verify(embeddings).save(stored.capture());
        given(embeddings.findByTenantIdAndSku("acme", "BOOTS-1"))
                .willReturn(Optional.of(stored.getValue()));

        assertThat(service.index("acme", "BOOTS-1", "Waterproof hiking boots, now with laces")).isTrue();
        verify(embedder, org.mockito.Mockito.times(2)).embedDocument(any());
    }

    @Test
    @DisplayName("changing the task prefix invalidates every stored vector")
    void prefixChangeForcesReindex() {
        // Documents and queries are embedded into different regions of the space. A vector
        // written with the old prefix is not comparable with one written with the new one, and
        // mixing them produces plausible wrong answers rather than an error.
        service.index("acme", "BOOTS-1", "Waterproof hiking boots");

        ArgumentCaptor<ProductEmbedding> stored = ArgumentCaptor.forClass(ProductEmbedding.class);
        verify(embeddings).save(stored.capture());
        given(embeddings.findByTenantIdAndSku("acme", "BOOTS-1"))
                .willReturn(Optional.of(stored.getValue()));

        properties.setDocumentPrefix("search_document_v2: ");

        assertThat(service.index("acme", "BOOTS-1", "Waterproof hiking boots")).isTrue();
    }

    @Test
    @DisplayName("a product with no text has its stale vector removed")
    void emptyTextRemovesStaleVector() {
        // Otherwise a withdrawn or emptied product keeps answering queries it no longer matches.
        assertThat(service.index("acme", "BOOTS-1", "   ")).isFalse();

        verify(embeddings).deleteByTenantIdAndSku("acme", "BOOTS-1");
        verify(embedder, never()).embedDocument(any());
    }

    @Test
    @DisplayName("indexing without a model reports unavailable rather than writing a bad vector")
    void refusesToIndexWithoutAModel() {
        given(embedder.isAvailable()).willReturn(false);

        assertThatThrownBy(() -> service.index("acme", "BOOTS-1", "Waterproof hiking boots"))
                .isInstanceOf(SemanticSearchUnavailableException.class);

        verify(embeddings, never()).save(any());
    }

    @Test
    @DisplayName("a blank tenant or sku is ignored rather than stored")
    void ignoresIncompleteIdentities() {
        assertThat(service.index(null, "BOOTS-1", "boots")).isFalse();
        assertThat(service.index("acme", "  ", "boots")).isFalse();

        verify(embeddings, never()).save(any());
    }

    @Test
    @DisplayName("one failing product does not abandon the rest of the reindex")
    void reindexSurvivesOneBadProduct() {
        // A partially indexed catalogue is still searchable. An empty one is not.
        given(embedder.embedDocument(any())).willAnswer(call -> {
            if ("BAD".equals(call.getArgument(0))) {
                throw new IllegalArgumentException("tokenised to nothing");
            }
            return new float[]{0.6f, 0.8f};
        });
        given(embeddings.findByTenantIdAndSku(any(), any())).willReturn(Optional.empty());

        int written = service.reindex("acme", List.of(
                new IndexableProduct("GOOD-1", "Boots", "Acme", "Waterproof"),
                new IndexableProduct("BAD", null, null, null),
                new IndexableProduct("GOOD-2", "Coat", "Acme", "Warm")));

        assertThat(written).isEqualTo(2);
    }

    @Test
    @DisplayName("name and brand lead the embedded text, description trails")
    void searchTextPutsIdentifiersFirst() {
        // On a truncated embedding the opening tokens carry the most weight, and a shopper's
        // query names the thing far more often than it paraphrases the description.
        assertThat(new IndexableProduct("S-1", "Waterproof Boots", "Acme", "Tall hiking boots")
                .searchText()).isEqualTo("Waterproof Boots Acme Tall hiking boots");
    }

    @Test
    @DisplayName("missing fields are omitted rather than rendered as blanks")
    void searchTextOmitsMissingFields() {
        assertThat(new IndexableProduct("S-1", null, "Acme", "Warm coat").searchText())
                .isEqualTo("Acme Warm coat");
        assertThat(new IndexableProduct("S-1", "Boots", null, null).searchText()).isEqualTo("Boots");
    }
}