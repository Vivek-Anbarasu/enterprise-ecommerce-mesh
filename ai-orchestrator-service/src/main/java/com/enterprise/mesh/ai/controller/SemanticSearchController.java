package com.enterprise.mesh.ai.controller;

import java.util.List;

import com.enterprise.mesh.ai.semantic.SemanticSearchService;
import com.enterprise.mesh.ai.semantic.SemanticSearchService.SemanticHit;
import com.enterprise.mesh.ai.semantic.SemanticSearchUnavailableException;

import lombok.RequiredArgsConstructor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Semantic product search.
 *
 * <p>A separate endpoint from {@link RecommendationController} rather than a mode of it. They
 * answer different questions - "what is this text about" versus "what suits this shopper" - and
 * merging them would make a model outage indistinguishable from a change in personalisation, on
 * the endpoint the storefront home page depends on.
 *
 * <p>Tenant resolution is identical to {@code RecommendationController}: the token claim is
 * authoritative and the header is only a fallback for service-to-service calls. A search that
 * could be scoped by a caller-supplied header would be a cross-tenant read, so the header can
 * never widen what the token already grants.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "Personalisation", description = "Recommendations, behavioural profile and semantic search")
@RequiredArgsConstructor
public class SemanticSearchController {

    private final SemanticSearchService searchService;

    public record SearchResponse(String query, List<SemanticHit> results, boolean semantic) {

        public SearchResponse {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Search the catalogue by meaning rather than by keyword")
    public SearchResponse search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @RequestParam("q") String query) {

        String tenant = tenant(jwt, tenantId);
        return new SearchResponse(query, searchService.search(tenant, query), true);
    }

    /**
     * Reports an unusable model as 503, not as an empty result set.
     *
     * <p>An empty list here would tell the storefront that the search box works and simply found
     * nothing, and it would show a shopper an empty page for a deployment that never had the
     * feature switched on. 503 says the capability is missing, which is the truth and something
     * the client can degrade around deliberately.
     */
    @ExceptionHandler(SemanticSearchUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ProblemDetail unavailable(SemanticSearchUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setTitle("Semantic search unavailable");
        return problem;
    }

    private String tenant(Jwt jwt, String headerTenant) {
        String fromToken = jwt == null ? null : jwt.getClaimAsString("tenant_id");
        if (fromToken != null && !fromToken.isBlank()) {
            return fromToken;
        }
        return headerTenant == null || headerTenant.isBlank() ? "public" : headerTenant;
    }
}