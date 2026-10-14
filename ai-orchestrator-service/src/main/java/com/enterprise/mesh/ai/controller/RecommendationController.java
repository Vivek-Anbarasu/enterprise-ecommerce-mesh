package com.enterprise.mesh.ai.controller;

import java.util.List;
import java.util.Map;

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

    private final RecommendationService recommendationService;

    public record InteractionRequest(String category, InteractionType interactionType) {
    }

    public record RecommendationResponse(List<String> preferredCategories,
                                         List<ScoredProduct> recommendations,
                                         String fallback) {
    }

    /**
     * Recommends products for the caller.
     *
     * <p>Returns an explicit {@code fallback} marker when there is no usable
     * history, so the storefront can show trending items rather than an empty
     * shelf.
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
        return new RecommendationResponse(preferred, List.of(), null);
    }

    @PostMapping("/interactions")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Record a view, add-to-cart or purchase signal")
    public ResponseEntity<Void> recordInteraction(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestBody InteractionRequest request) {
        recommendationService.recordInteraction(jwt.getSubject(), tenant(jwt, tenantId),
                request.category(), request.interactionType());
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
