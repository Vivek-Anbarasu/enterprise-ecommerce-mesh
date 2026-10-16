package com.enterprise.mesh.ai.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;

/**
 * Coarse interaction signal linking a shopper to a category.
 *
 * <p>Deliberately coarse (category, not SKU) and short-lived. Behavioural
 * profiles built from fine-grained history are both a privacy liability and
 * largely self-defeating: they generalise badly and expire quickly.
 *
 * <p>Retention is enforced by {@code expiresAt} plus a TTL index, so a shopper's
 * profile disappears automatically rather than requiring a deletion job.
 *
 * <p><b>Read-only, deliberately.</b> {@code @Getter} only - there is no
 * {@code @Setter}, because {@link #recordInteraction} updates three fields
 * together and the three must move as a unit:
 *
 * <ul>
 *   <li>{@code score} applies exponential decay before adding the new weight.
 *       A setter would let a caller overwrite the score outright and skip the
 *       decay, which is the entire reason stale preference fades.</li>
 *   <li>{@code interactionCount} and {@code lastInteractionAt} only move when an
 *       interaction is actually recorded.</li>
 *   <li>{@code expiresAt} is the TTL index target and must always be derived from
 *       {@code lastInteractionAt}. Setting them independently lets a profile
 *       outlive the evidence it was built from - a retention bug that is invisible
 *       until someone asks why a dormant shopper still has a profile.</li>
 * </ul>
 *
 * <p>{@code customerId} and {@code category} form the compound index key and are
 * likewise fixed at construction.
 */
@Getter
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
}
