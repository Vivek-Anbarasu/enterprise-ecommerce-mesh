package com.enterprise.mesh.review.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Getter;

/**
 * A product review.
 *
 * <p>One review per customer per product <em>per tenant</em>, enforced by a
 * unique compound index rather than a read-then-write check: two concurrent
 * submissions would otherwise both pass the check and create duplicates.
 *
 * <p>tenantId leads every index here, matching the queries in
 * {@code ReviewService} and the mesh-wide rule that the tenant is part of every
 * unique index. The previous definition omitted it, which did two things: it
 * let two tenants collide on the same (productId, customerId) pair, and the
 * {productId, moderationStatus, createdAt} read index could not serve a query
 * that filters on tenantId.
 *
 * <p>Migration: Spring creates the new indexes but never drops the old ones, so
 * {@code ux_review_product_customer} and {@code idx_review_product_visible} must
 * be dropped explicitly. See docker/mongo-init-reviews.js.
 *
 * <p><b>Read-only, deliberately.</b> {@code @Getter} only - there is no
 * {@code @Setter}. Each mutable field is owned by exactly one of the three
 * behaviour methods below, and several of them validate on the way in:
 *
 * <ul>
 *   <li>{@link #edit} re-checks the 1-5 rating range, so a setter would let a
 *       moderator's edit store a rating the constructor would have rejected.</li>
 *   <li>{@link #moderate} owns {@code moderationStatus}; the moderation pipeline
 *       is the only thing that should move it.</li>
 *   <li>{@link #markHelpful} increments {@code helpfulCount}. A setter would make
 *       the count a value rather than a tally, which is the one thing it must not
 *       be.</li>
 * </ul>
 *
 * <p>{@code productId}, {@code tenantId} and {@code customerId} form the unique
 * compound index and are fixed at construction.
 */
@Getter
@Document(collection = "reviews")
@CompoundIndex(name = "ux_review_tenant_product_customer",
        def = "{'tenantId': 1, 'productId': 1, 'customerId': 1}", unique = true)
@CompoundIndex(name = "idx_review_tenant_product_visible",
        def = "{'tenantId': 1, 'productId': 1, 'moderationStatus': 1, 'createdAt': -1}")
public class Review {

    @Id
    private String id;

    private String productId;

    private String tenantId;

    private String customerId;

    /** Denormalised for display; the customer service remains the source of truth. */
    private String customerDisplayName;

    private int rating;

    private String title;

    private String body;

    private List<String> imageUrls = new ArrayList<>();

    private boolean verifiedPurchase;

    private int helpfulCount;

    private ModerationStatus moderationStatus = ModerationStatus.PENDING;

    private Instant createdAt;

    private Instant updatedAt;

    protected Review() {
        // required by Mongo
    }

    /** Where a review sits in the moderation pipeline. */
    public enum ModerationStatus {
        /** Awaiting automated and/or human review. */
        PENDING,
        PUBLISHED,
        REJECTED,
        FLAGGED
    }

    public Review(String id, String productId, String tenantId, String customerId,
                  String customerDisplayName, int rating, String title, String body,
                  boolean verifiedPurchase) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        this.id = id;
        this.productId = productId;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.customerDisplayName = customerDisplayName;
        this.rating = rating;
        this.title = title;
        this.body = body;
        this.verifiedPurchase = verifiedPurchase;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Republishes a rejected review once a moderator overturns the decision. */
    public void edit(int rating, String title, String body) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }
        this.rating = rating;
        this.title = title;
        this.body = body;
        this.updatedAt = Instant.now();
    }

    public void moderate(ModerationStatus status) {
        this.moderationStatus = status;
        this.updatedAt = Instant.now();
    }

    public void markHelpful() {
        this.helpfulCount++;
    }
}
