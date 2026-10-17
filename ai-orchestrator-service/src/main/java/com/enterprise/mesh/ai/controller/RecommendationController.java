package com.enterprise.mesh.ai.controller;

import java.util.List;
import java.util.Map;

import com.enterprise.mesh.ai.service.CatalogueClient;
import com.enterprise.mesh.ai.service.RecommendationService;
import com.enterprise.mesh.ai.service.RecommendationService.InteractionType;
import com.enterprise.mesh.ai.service.RecommendationService.ScoredProduct;

import lombok.RequiredArgsConstructor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
@Tag(name = "Personalisation", description = "Recommendations and behavioural profile")
@RequiredArgsConstructor
public class RecommendationController {

    /**
     * How many of the shopper's preferred categories to pull candidates for.
     *
     * <p>A cap rather than a setting: it bounds the per-request catalogue fan-out on
     * an endpoint the storefront hits on every page load, and the heuristic's decay
     * makes the tail worthless anyway. Raising it past the fifth category would cost
     * an HTTP call per shopper per load to move scores that are already ~0.16 of the
     * leading category's.
     */
    private static final int MAX_CANDIDATE_CATEGORIES = 5;

    /** Products fetched per category. Well above {@code mesh.ai.max-recommendations}. */
    private static final int CANDIDATES_PER_CATEGORY = 20;

    private final RecommendationService recommendationService;
    private final CatalogueClient catalogueClient;

    /**
     * A product view, reported by the browser.
     *
     * <p><b>Deliberately has no interactionType.</b> Purchases and add-to-carts used to
     * be reportable here and no longer are: {@code order.paid} now reaches
     * {@code OrderEventListener} over Kafka, and the add-to-cart feed is still to come.
     * A caller-supplied type on this endpoint is exactly what let anyone assert a
     * purchase - the heaviest weight in the model - against a profile they do not own.
     * Dropping the field makes that structurally impossible rather than a validation
     * rule someone can route around.
     *
     * <p>Views stay client-side because nothing else in the mesh observes a page being
     * rendered. They weigh 1 for that reason, against 8 for a purchase.
     */
    public record ViewRequest(String category) {
    }

    public record RecommendationResponse(List<String> preferredCategories,
                                         List<ScoredProduct> recommendations,
                                         String fallback) {
    }

    /**
     * Recommends products for the caller.
     *
     * <p>Returns an explicit {@code fallback} marker when there is nothing to rank, so
     * the storefront can show trending items rather than an empty shelf. That covers
     * both reasons there is nothing: no usable history, and a catalogue that had no
     * products in the shopper's categories.
     */
    @GetMapping("/recommendations")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Personalised recommendations for the caller")
    public RecommendationResponse recommendations(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        String tenant = tenant(jwt, tenantId);
        List<String> preferred =
                recommendationService.preferredCategories(jwt.getSubject(), tenant);

        if (preferred.isEmpty()) {
            return new RecommendationResponse(List.of(), List.of(), "trending");
        }

        // One catalogue call per category, so the list is capped. The cap is not free
        // - a shopper whose preferences all sit in their sixth-best category gets a
        // weaker shelf - but the decay is 1/(1+index), so the fifth category already
        // scores 0.16 against the first's 1.0 and the tail past that is noise.
        // Bounding it keeps the storefront's hottest read to a fixed number of HTTP
        // calls rather than one per preference.
        List<String> categories = preferred.size() > MAX_CANDIDATE_CATEGORIES
                ? preferred.subList(0, MAX_CANDIDATE_CATEGORIES)
                : preferred;

        // Popularity is passed empty on purpose: nothing in the mesh computes it, so
        // the popularity term is currently dead arithmetic. See RecommendationService#rank.
        List<ScoredProduct> ranked = recommendationService.rank(
                catalogueClient.candidates(tenant, categories, CANDIDATES_PER_CATEGORY),
                preferred, Map.of());

        // A catalogue blip, or a shopper whose categories hold nothing, lands here
        // rather than returning an empty row the storefront cannot render.
        return ranked.isEmpty()
                ? new RecommendationResponse(preferred, List.of(), "trending")
                : new RecommendationResponse(preferred, ranked, null);
    }

    /**
     * Records that the caller looked at a product.
     *
     * <p>Fire-and-forget from the SPA: the shopper should never wait on it, and losing
     * one costs a single weight unit against an 8-unit purchase. Best sent through the
     * BFF like every other browser call, so the SPA holds no token and the request
     * carries the session cookie.
     */
    @PostMapping("/interactions")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Record a product view signal")
    public ResponseEntity<Void> recordInteraction(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestBody ViewRequest request) {
        recommendationService.recordInteraction(jwt.getSubject(), tenant(jwt, tenantId),
                request.category(), InteractionType.VIEW);
        return ResponseEntity.noContent().build();
    }

    /** Right to be forgotten; erases the behavioural profile immediately. */
    @DeleteMapping("/profile")
    @PreAuthorize("hasAnyRole('CUSTOMER')")
    @Operation(summary = "Erase the caller's behavioural profile")
    public ResponseEntity<Void> forget(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        recommendationService.forget(jwt.getSubject(), tenant(jwt, tenantId));
        return ResponseEntity.noContent().build();
    }

    private String tenant(Jwt jwt, String headerTenant) {
        String fromToken = jwt == null ? null : jwt.getClaimAsString("tenant_id");
        if (fromToken != null && !fromToken.isBlank()) {
            return fromToken;
        }
        return headerTenant == null || headerTenant.isBlank() ? "public" : headerTenant;
    }
}
