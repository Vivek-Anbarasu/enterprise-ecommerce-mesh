package com.enterprise.mesh.review.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A product review.
 *
 * <p>One review per customer per product, enforced by a unique compound index
 * rather than a read-then-write check: two concurrent submissions would
 * otherwise both pass the check and create duplicates.
 */
@Document(collection = "reviews")
@CompoundIndex(name = "ux_review_product_customer", def = "{'productId': 1, 'customerId': 1}",
        unique = true)
@CompoundIndex(name = "idx_review_product_visible", def = "{'productId': 1, 'moderationStatus': 1, 'createdAt': -1}")
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

    public String getId() {
        return id;
    }

    public String getProductId() {
        return productId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCustomerDisplayName() {
        return customerDisplayName;
    }

    public int getRating() {
        return rating;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public List<String> getImageUrls() {
        return imageUrls;
    }

    public boolean isVerifiedPurchase() {
        return verifiedPurchase;
    }

    public int getHelpfulCount() {
        return helpfulCount;
    }

    public ModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
