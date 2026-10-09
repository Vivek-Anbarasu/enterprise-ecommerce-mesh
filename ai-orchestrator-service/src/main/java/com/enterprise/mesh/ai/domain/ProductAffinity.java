package com.enterprise.mesh.ai.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Coarse interaction signal linking a shopper to a category.
 *
 * <p>Deliberately coarse (category, not SKU) and short-lived. Behavioural
 * profiles built from fine-grained history are both a privacy liability and
 * largely self-defeating: they generalise badly and expire quickly.
 *
 * <p>Retention is enforced by {@code expiresAt} plus a TTL index, so a shopper's
 * profile disappears automatically rather than requiring a deletion job.
 */
@Document(collection = "product_affinities")
@CompoundIndex(name = "idx_affinity_customer_category",
        def = "{'customerId': 1, 'category': 1}")
public class ProductAffinity {

    @Id
    private String id;

    private String customerId;

    private String tenantId;

    private String category;

    /** Weighted interaction score; higher means stronger preference. */
    private double score;

    private int interactionCount;

    private Instant lastInteractionAt;

    /** TTL index target: the document is removed by Mongo once this passes. */
    private Instant expiresAt;

    protected ProductAffinity() {
        // required by Mongo
    }

    public ProductAffinity(String id, String customerId, String tenantId, String category) {
        this.id = id;
        this.customerId = customerId;
        this.tenantId = tenantId;
        this.category = category;
        this.lastInteractionAt = Instant.now();
        this.expiresAt = this.lastInteractionAt.plusSeconds(90L * 24 * 60 * 60);
    }

    /** Records an interaction, with decay so stale preference fades. */
    public void recordInteraction(double weight) {
        // Exponential decay keeps a long-dormant shopper from being pinned to
        // whatever they liked months ago.
        this.score = (this.score * 0.85d) + weight;
        this.interactionCount++;
        this.lastInteractionAt = Instant.now();
        this.expiresAt = this.lastInteractionAt.plusSeconds(90L * 24 * 60 * 60);
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCategory() {
        return category;
    }

    public double getScore() {
        return score;
    }

    public int getInteractionCount() {
        return interactionCount;
    }

    public Instant getLastInteractionAt() {
        return lastInteractionAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
