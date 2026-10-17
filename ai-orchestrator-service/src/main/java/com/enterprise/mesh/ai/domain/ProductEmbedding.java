package com.enterprise.mesh.ai.domain;

import java.time.Instant;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A catalogue product encoded into a vector, for brute-force similarity search.
 *
 * <p><b>Derived state, deliberately not the source of truth.</b> The product itself lives in
 * {@code product-catalog-service}. This is a projection that can be deleted and rebuilt from that
 * catalogue at any time, which is the same relationship {@code product-caches} has to products
 * elsewhere in the mesh. It exists because a scan over one tenant's vectors has to be fast enough
 * to serve a search request, and reading the catalogue and tokenising on the request path does
 * neither.
 *
 * <p><b>Tenant-first unique index, as everywhere else in the mesh.</b> {@code (tenantId, sku)}
 * rather than sku alone: two tenants are independent catalogues and may both stock
 * {@code SKU-1001}. A sku-only key would let one tenant's embedding overwrite the other's, and
 * the search would then answer with the wrong tenant's product - a data leak that is invisible
 * in a single-tenant dev environment.
 */
@Document(collection = "product_embeddings")
@CompoundIndex(name = "ux_embedding_tenant_sku", def = "{'tenantId': 1, 'sku': 1}", unique = true)
@Getter
@Setter
public class ProductEmbedding {

    @Id
    private String id;

    @Indexed
    private String sku;

    @Indexed
    private String tenantId;

    /**
     * Unit-length vector, one float per model dimension.
     *
     * <p>Stored as {@code List<Double>} rather than a binary blob because Mongo has no vector type
     * here: standalone {@code mongod} supports neither Atlas Vector Search nor a native vector
     * index. The storage cost is accepted in exchange for the scan staying in one collection.
     */
    private List<Double> vector;

    /**
     * Fingerprint of the exact text that was embedded.
     *
     * <p>Exists so a reindex can skip unchanged products. Embedding is the expensive part of an
     * index rebuild - tokenisation and inference, not the database write - and a merchant
     * re-saving a product without editing its description should not re-run a model over it.
     */
    private String textFingerprint;

    /** Which model produced this vector. */
    private String model;

    /**
     * The task prefix in force when this vector was written.
     *
     * <p>Part of the identity of the vector, not a comment. nomic-embed-text embeds documents and
     * queries into different regions of the space, so a vector written with
     * {@code search_document:} is not comparable with one written without. Recorded so a prefix
     * change forces a reindex instead of mixing two incompatible vector spaces into one
     * collection, which would produce confidently wrong results rather than an error.
     */
    private String textPrefix;

    private Instant indexedAt;

    public ProductEmbedding() {
        // required by Mongo
    }
}