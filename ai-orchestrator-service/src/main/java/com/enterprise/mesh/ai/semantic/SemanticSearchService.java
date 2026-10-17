package com.enterprise.mesh.ai.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.enterprise.mesh.ai.domain.ProductEmbedding;
import com.enterprise.mesh.ai.repository.ProductEmbeddingRepository;

/**
 * Brute-force cosine similarity over one tenant's vectors.
 *
 * <p><b>Read side only, and separate from {@code RecommendationService} on purpose.</b> That
 * service's heuristic is the definitive ranking rule and is not touched by this. Search answers a
 * different question - "what in the catalogue is this text about" - and conflating the two would
 * make a model outage look like a change in personalisation.
 *
 * <p><b>Why a scan rather than a vector index.</b> {@code findNearest} is a MongoDB Atlas feature
 * and this deployment is a standalone {@code mongod}, where it does not exist. At the catalogue
 * size this project targets a few thousand 768-dimension floats is a few megabytes, and the
 * arithmetic is single-digit milliseconds. Introducing a vector datastore to avoid that would add
 * a deployment, a backup story and an outage mode to save time nobody is waiting on. The
 * crossover - roughly ten thousand products - is recorded here because it is the point at which
 * this class should be replaced, not tuned.
 *
 * <p><b>Vectors from a different model are skipped, not scored.</b> A collection written by an
 * older encoder would otherwise be compared against the current query, and cosine similarity
 * between two unrelated spaces still returns a confident-looking number.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticSearchService {

    private final ProductEmbeddingRepository embeddings;
    private final TextEmbedder embedder;
    private final SemanticProperties properties;

    /**
     * Returns the tenant's best-matching products for a query.
     *
     * @throws SemanticSearchUnavailableException if the embedding model is not loaded
     */
    public List<SemanticHit> search(String tenantId, String query) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (query == null || query.isBlank()) {
            // An empty query is a client bug, not a search that matched nothing. Returning an
            // empty list here would make a broken storefront search box look like a working one.
            throw new IllegalArgumentException("query must not be blank");
        }
        if (!embedder.isAvailable()) {
            throw new SemanticSearchUnavailableException(
                    "Semantic search is unavailable: the embedding model is not loaded. "
                            + "Recommendations are unaffected.");
        }

        float[] queryVector = embedder.embedQuery(query);
        List<ProductEmbedding> candidates = embeddings.findByTenantId(tenantId);
        if (candidates.isEmpty()) {
            return List.of();
        }

        String currentModel = properties.getModelId();
        List<SemanticHit> hits = new ArrayList<>(Math.min(candidates.size(), properties.getTopK()));
        int foreignVectors = 0;

        for (ProductEmbedding candidate : candidates) {
            if (candidate.getVector() == null || candidate.getVector().isEmpty()) {
                continue;
            }
            if (!currentModel.equals(candidate.getModel())) {
                foreignVectors++;
                continue;
            }
            if (candidate.getVector().size() != queryVector.length) {
                // A width mismatch is the other symptom of a swapped model, and would otherwise
                // read as "these two things have nothing in common" for every row.
                foreignVectors++;
                continue;
            }

            double score = Vectors.cosine(queryVector, SemanticIndexService.toArray(candidate.getVector()));
            if (score < properties.getMinimumScore()) {
                continue;
            }
            hits.add(new SemanticHit(candidate.getSku(), score));
        }

        if (foreignVectors > 0) {
            log.warn("Skipped {}/{} vectors for tenant {} written by a different model or width. "
                    + "Reindex before trusting these results.", foreignVectors, candidates.size(), tenantId);
        }

        return hits.stream()
                // Ties broken by SKU for the same reason rank() does: catalogue order is not a
                // stable thing to hand a shopper, and a search that reshuffles between two
                // identical requests reads as a bug.
                .sorted(Comparator.comparingDouble(SemanticHit::score).reversed()
                        .thenComparing(SemanticHit::sku))
                .limit(properties.getTopK())
                .toList();
    }

    /** A product that matched, and how strongly. */
    public record SemanticHit(String sku, double score) {
    }
}