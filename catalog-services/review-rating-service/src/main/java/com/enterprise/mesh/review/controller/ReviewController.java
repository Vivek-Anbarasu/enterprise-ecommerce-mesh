package com.enterprise.mesh.review.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.enterprise.mesh.review.domain.Review;
import com.enterprise.mesh.review.domain.Review.ModerationStatus;
import com.enterprise.mesh.review.service.ReviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews", description = "Product reviews and ratings")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    public record SubmitReviewRequest(
            @NotBlank @Size(max = 64) String productId,
            @Min(1) @Max(5) int rating,
            @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String body) {
    }

    public record ReviewResponse(String id, String productId, String customerDisplayName,
                                 int rating, String title, String body, int helpfulCount,
                                 boolean verifiedPurchase, ModerationStatus status) {

        static ReviewResponse from(Review review) {
            return new ReviewResponse(review.getId(), review.getProductId(),
                    review.getCustomerDisplayName(), review.getRating(), review.getTitle(),
                    review.getBody(), review.getHelpfulCount(), review.isVerifiedPurchase(),
                    review.getModerationStatus());
        }
    }

    @GetMapping("/products/{productId}")
    @Operation(summary = "Published reviews for a product, newest first")
    public List<ReviewResponse> forProduct(
            @PathVariable String productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return reviewService.listPublished(productId, tenant(tenantId), page, size).stream()
                .map(ReviewResponse::from)
                .toList();
    }

    @GetMapping("/products/{productId}/summary")
    @Operation(summary = "Rating average and distribution for a product")
    public ReviewService.RatingSummary summary(
            @PathVariable String productId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return reviewService.summarise(productId, tenant(tenantId));
    }

    /** Submits or edits the caller's review for a product. */
    @PostMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Submit or edit the caller's review")
    public ResponseEntity<ReviewResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestBody SubmitReviewRequest request) {
        Review review = reviewService.submit(request.productId(), tenant(tenantId),
                jwt.getSubject(), jwt.getClaimAsString("name"), request.rating(),
                request.title(), request.body(), false);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewResponse.from(review));
    }

    @PostMapping("/{reviewId}/helpful")
    @Operation(summary = "Mark a review as helpful")
    public ResponseEntity<Void> helpful(
            @PathVariable String reviewId,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        reviewService.markHelpful(reviewId, tenant(tenantId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{reviewId}/moderation")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set moderation status")
    public ResponseEntity<Void> moderate(
            @PathVariable String reviewId,
            @RequestParam ModerationStatus status,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        reviewService.moderate(reviewId, status, tenant(tenantId));
        return ResponseEntity.noContent().build();
    }

    private String tenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "public" : tenantId;
    }

    @RestControllerAdvice
    static class ReviewExceptionHandler {

        @ExceptionHandler(IllegalArgumentException.class)
        ProblemDetail handleBadRequest(IllegalArgumentException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
            problem.setTitle("Invalid Review");
            problem.setType(URI.create("https://errors.enterprise-ecommerce.com/review/invalid-request"));
            return problem;
        }
    }
}
