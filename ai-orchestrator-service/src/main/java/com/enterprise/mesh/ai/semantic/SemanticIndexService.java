package com.enterprise.mesh.ai.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import com.enterprise.mesh.ai.domain.ProductEmbedding;
import com.enterprise.mesh.ai.repository.ProductEmbeddingRepository;

/**
 * Keeps the vector collection in step with the catalogue.
 *
 * <p><b>Write side only, and deliberately so.</b> Nothing here decides what a shopper sees - that
 * stays with {@code RecommendationService}, whose heuristic is the definitive ranking rule.
 * This only maintains a projection that exists so search has something to scan.
 *
 * <p><b>Skipping unchanged products is the point of this class.</b> A full reindex runs a model
 * over every product, and on CPU that is the slowest thing in the service by orders of magnitude.
 * Fingerprinting the exact embedded text means a reindex after a price change costs no inference
 * at all, and a merchant re-saving a description they did not edit also costs nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticIndexService {

    private static final String FINGERPRINT_ALGORITHM = "SHA-256";

    private final ProductEmbeddingRepository embeddings;
    private final TextEmbedder embedder;
    private final SemanticProperties properties;

    /**
     * Embeds one product, unless it is already indexed from identical text.
     *
     * @return true if the collection was written
     */
    public boolean index(String tenantId, String sku, String text) {
        if (tenantId == null || tenantId.isBlank() || sku == null || sku.isBlank()) {
            return false;
        }
        String source = text == null ? "" : text.strip();
        if (source.isEmpty()) {
            // A product with no text cannot be embedded and cannot be found by search. Deleting
            // a stale vector is better than leaving it: otherwise a withdrawn or emptied product
            // keeps answering queries it no longer matches.
            embeddings.deleteByTenantIdAndSku(tenantId, sku);
            return false;
        }
        if (!embedder.isAvailable()) {
            throw new SemanticSearchUnavailableException(
                    "Cannot index: the embedding model is not available");
        }

        String fingerprint = fingerprint(source);
        Optional<ProductEmbedding> existing = embeddings.findByTenantIdAndSku(tenantId, sku);
        if (existing.isPresent() && fingerprint.equals(existing.get().getTextFingerprint())
                && properties.getDocumentPrefix().equals(existing.get().getTextPrefix())) {
            return false;
        }

        float[] vector = embedder.embedDocument(source);
        ProductEmbedding document = existing.orElseGet(ProductEmbedding::new);
        document.setTenantId(tenantId);
        document.setSku(sku);
        document.setVector(toList(vector));
        document.setTextFingerprint(fingerprint);
        // Recorded so a future model change does not silently mix vector spaces. Two models'
        // outputs are not comparable, and a collection holding both produces confidently wrong
        // results rather than an error.
        document.setModel(properties.getModelId());
        document.setTextPrefix(properties.getDocumentPrefix());
        document.setIndexedAt(Instant.now());
        embeddings.save(document);
        return true;
    }

    /**
     * Rebuilds one tenant's vectors from the supplied products.
     *
     * <p>Does not delete first: deleting would make a reindex unable to tell an unchanged product
     * from a missing one, and a partial failure would leave the tenant with no searchable products
     * rather than stale ones. Stale vectors are worse than none, but only marginally, and they
     * carry an {@code indexedAt} that a sweeper can act on.
     *
     * @param products products to index, already fetched by the caller
     * @return how many were (re)embedded
     */
    public int reindex(String tenantId, List<IndexableProduct> products) {
        if (!embedder.isAvailable()) {
            throw new SemanticSearchUnavailableException(
                    "Cannot reindex: the embedding model is not available");
        }
        int written = 0;
        for (IndexableProduct product : products) {
            try {
                if (index(tenantId, product.sku(), product.searchText())) {
                    written++;
                }
            } catch (RuntimeException e) {
                // One malformed product must not abandon the rest of the tenant. Log it and
                // carry on; a partially indexed catalogue is still searchable, an empty one
                // is not.
                log.warn("Skipping product sku={} tenant={} during reindex: {}",
                        product.sku(), tenantId, e.toString());
            }
        }
        log.info("Reindexed tenant={} products={} rewritten={}", tenantId, products.size(), written);
        return written;
    }

    /** What gets embedded. Deliberately not the whole {@code Product}. */
    public record IndexableProduct(String sku, String name, String brand, String description) {

        /**
         * The exact text handed to the model, and therefore the exact text fingerprinted.
         *
         * <p>Name first, brand next, description last: on a truncated embedding the opening
         * tokens carry the most weight, and a shopper's query is far more likely to name the
         * thing than to paraphrase its description.
         */
        public String searchText() {
            return join(" ", name, brand, description);
        }

        private static String join(String separator, String... parts) {
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                if (!builder.isEmpty()) {
                    builder.append(separator);
                }
                builder.append(part.strip());
            }
            return builder.toString();
        }
    }

    static List<Double> toList(float[] vector) {
        List<Double> values = new ArrayList<>(vector.length);
        for (float component : vector) {
            values.add((double) component);
        }
        return values;
    }

    static float[] toArray(List<Double> stored) {
        float[] vector = new float[stored.size()];
        for (int i = 0; i < stored.size(); i++) {
            vector[i] = stored.get(i).floatValue();
        }
        return vector;
    }

    /**
     * Fingerprint of the text that was embedded.
     *
     * <p>Covers the prefix as well as the body, so switching the model prefix re-indexes rather
     * than leaving the collection holding vectors from the old task.
     */
    private String fingerprint(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance(FINGERPRINT_ALGORITHM);
            byte[] hash = digest.digest(
                    (properties.getDocumentPrefix() + source).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; if it is genuinely absent, failing to index
            // is better than indexing everything on every run.
            throw new IllegalStateException(FINGERPRINT_ALGORITHM + " is unavailable", e);
        }
    }
}