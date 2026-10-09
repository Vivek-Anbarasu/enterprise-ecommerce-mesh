package com.enterprise.mesh.review.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.enterprise.mesh.review.domain.Review;
import com.enterprise.mesh.review.domain.Review.ModerationStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Reviews, rating aggregates and moderation.
 *
 * <p>Only PUBLISHED reviews are ever returned to shoppers, and the rating
 * summary is computed from that same filtered set - otherwise a product could
 * show a 4.8 average derived from reviews nobody is allowed to read.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final MongoTemplate mongoTemplate;

    public ReviewService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Creates or replaces the caller's review for a product.
     *
     * <p>Upsert semantics: a shopper editing their review must not create a
     * second entry.
     */
    public Review submit(String productId, String tenantId, String customerId,
                         String customerDisplayName, int rating, String title, String body,
                         boolean verifiedPurchase) {
        Query query = Query.query(Criteria.where("productId").is(productId)
                .and("customerId").is(customerId)
                .and("tenantId").is(tenantId));

        Review existing = mongoTemplate.findOne(query, Review.class);
        if (existing != null) {
            existing.edit(rating, title, body);
            log.info("Review updated for product={} customer={}", productId, customerId);
            return mongoTemplate.save(existing);
        }

        Review review = new Review(UUID.randomUUID().toString(), productId, tenantId, customerId,
                customerDisplayName, rating, title, body, verifiedPurchase);
        // Auto-publish unless the content looks like abuse; a real deployment
        // would call a classifier here.
        review.moderate(looksAbusive(body) ? ModerationStatus.FLAGGED : ModerationStatus.PUBLISHED);
        log.info("Review submitted for product={} rating={} status={}",
                productId, rating, review.getModerationStatus());
        return mongoTemplate.save(review);
    }

    public List<Review> listPublished(String productId, String tenantId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        Query query = Query.query(Criteria.where("productId").is(productId)
                        .and("tenantId").is(tenantId)
                        .and("moderationStatus").is(ModerationStatus.PUBLISHED))
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip(Math.max(page, 0) * safeSize)
                .limit(safeSize);
        return mongoTemplate.find(query, Review.class);
    }

    /** Rating summary computed only over published reviews. */
    public RatingSummary summarise(String productId, String tenantId) {
        List<Review> published = mongoTemplate.find(
                Query.query(Criteria.where("productId").is(productId)
                        .and("tenantId").is(tenantId)
                        .and("moderationStatus").is(ModerationStatus.PUBLISHED)),
                Review.class);

        if (published.isEmpty()) {
            return new RatingSummary(productId, 0d, 0, java.util.Map.of());
        }

        double sum = published.stream().mapToInt(Review::getRating).sum();
        java.util.Map<Integer, Long> distribution = published.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Review::getRating, java.util.stream.Collectors.counting()));

        return new RatingSummary(productId,
                Math.round((sum / published.size()) * 100d) / 100d,
                published.size(),
                distribution);
    }

    public void markHelpful(String reviewId, String tenantId) {
        Review review = mongoTemplate.findOne(
                Query.query(Criteria.where("id").is(reviewId).and("tenantId").is(tenantId)),
                Review.class);
        if (review == null) {
            return;
        }
        review.markHelpful();
        mongoTemplate.save(review);
    }

    @org.springframework.transaction.annotation.Transactional
    public void moderate(String reviewId, ModerationStatus status, String tenantId) {
        Query query = Query.query(Criteria.where("id").is(reviewId).and("tenantId").is(tenantId));
        Review review = mongoTemplate.findOne(query, Review.class);
        if (review == null) {
            throw new IllegalArgumentException("Unknown review " + reviewId);
        }
        review.moderate(status);
        mongoTemplate.save(review);
        log.info("Review {} moved to {}", reviewId, status);
    }

    /**
     * Cheap heuristic standing in for a real moderation classifier. Kept
     * deliberately simple so the behaviour is predictable in tests.
     */
    private boolean looksAbusive(String body) {
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("http://") || lower.contains("https://");
    }

    /** Aggregate rating for a product. */
    public record RatingSummary(String productId, double average, long count,
                                java.util.Map<Integer, Long> distribution) {
    }
}
