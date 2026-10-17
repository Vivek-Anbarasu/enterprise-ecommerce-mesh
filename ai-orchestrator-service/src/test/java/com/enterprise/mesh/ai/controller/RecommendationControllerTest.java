package com.enterprise.mesh.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.enterprise.mesh.ai.controller.RecommendationController.RecommendationResponse;
import com.enterprise.mesh.ai.service.CatalogueClient;
import com.enterprise.mesh.ai.service.RecommendationService;
import com.enterprise.mesh.ai.service.RecommendationService.Candidate;
import com.enterprise.mesh.ai.service.RecommendationService.ScoredProduct;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The storefront's home page read.
 *
 * <p>This endpoint previously returned an empty recommendation list on both of its
 * branches, including the one for a shopper who does have preferences. These tests pin
 * that it now ranks, because the failure mode is invisible: the response shape is
 * unchanged, the HTTP status is 200, and the storefront renders a correct-looking
 * trending row every time. Nothing but an assertion on the list would have caught it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private CatalogueClient catalogueClient;

    @Mock
    private Jwt jwt;

    private RecommendationController controller;

    @BeforeEach
    void setUp() {
        controller = new RecommendationController(recommendationService, catalogueClient);
        given(jwt.getSubject()).willReturn("cust-1");
        given(jwt.getClaimAsString("tenant_id")).willReturn(null);
    }

    private void hasPreferences(String... categories) {
        given(recommendationService.preferredCategories("cust-1", "acme"))
                .willReturn(List.of(categories));
    }

    @Test
    @DisplayName("a shopper with preferences gets ranked products, not an empty list")
    void returnsRankedRecommendations() {
        hasPreferences("boots", "coats");
        given(catalogueClient.candidates(anyString(), anyList(), anyInt()))
                .willReturn(List.of(new Candidate("B-1", "boots"), new Candidate("C-1", "coats")));
        given(recommendationService.rank(anyList(), anyList(), any()))
                .willReturn(List.of(new ScoredProduct("B-1", "boots", 0.8d)));

        RecommendationResponse response = controller.recommendations(jwt, "acme");

        assertThat(response.recommendations()).extracting(ScoredProduct::sku).containsExactly("B-1");
        assertThat(response.fallback())
                .as("A row with products is personalised, so it must not carry the trending marker")
                .isNull();
    }

    @Test
    @DisplayName("popularity is passed empty, because nothing computes it")
    void passesAnEmptyPopularityMap() {
        hasPreferences("boots");
        given(catalogueClient.candidates(anyString(), anyList(), anyInt())).willReturn(List.of());
        given(recommendationService.rank(anyList(), anyList(), any())).willReturn(List.of());

        controller.recommendations(jwt, "acme");

        verify(recommendationService).rank(anyList(), anyList(), eq(Map.of()));
    }

    @Test
    @DisplayName("a shopper with no history falls back to trending without touching the catalogue")
    void newShopperFallsBackWithoutACatalogueCall() {
        // Nothing to rank, so asking the catalogue for candidates would be a wasted
        // fan-out on the storefront's hottest read - for the majority of shoppers.
        given(recommendationService.preferredCategories("cust-1", "acme")).willReturn(List.of());

        RecommendationResponse response = controller.recommendations(jwt, "acme");

        assertThat(response.fallback()).isEqualTo("trending");
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.preferredCategories()).isEmpty();
        verify(catalogueClient, never()).candidates(anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("a catalogue with nothing in those categories falls back rather than returning a bare row")
    void catalogueWithNoMatchingProductsFallsBack() {
        hasPreferences("boots");
        given(catalogueClient.candidates(anyString(), anyList(), anyInt())).willReturn(List.of());
        given(recommendationService.rank(anyList(), anyList(), any())).willReturn(List.of());

        RecommendationResponse response = controller.recommendations(jwt, "acme");

        assertThat(response.fallback())
                .as("An empty row is unrenderable; the trending marker is what tells the "
                        + "storefront to substitute something")
                .isEqualTo("trending");
        assertThat(response.recommendations()).isEmpty();
        // The preferences are still real and still worth telling the client about.
        assertThat(response.preferredCategories()).containsExactly("boots");
    }

    @Test
    @DisplayName("the catalogue is asked about at most five categories, however many the shopper has")
    void capsThePerRequestCatalogueFanOut() {
        // One HTTP call per category, on an endpoint hit on every page load. A shopper
        // with twelve recorded categories must not cost twelve calls.
        List<String> many = new ArrayList<>(List.of(
                "boots", "coats", "kitchen", "garden", "books", "toys", "sports"));
        given(recommendationService.preferredCategories("cust-1", "acme")).willReturn(many);
        given(catalogueClient.candidates(anyString(), anyList(), anyInt())).willReturn(List.of());
        given(recommendationService.rank(anyList(), anyList(), any())).willReturn(List.of());

        controller.recommendations(jwt, "acme");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
        verify(catalogueClient).candidates(eq("acme"), asked.capture(), anyInt());
        assertThat(asked.getValue()).containsExactly("boots", "coats", "kitchen", "garden", "books");
    }

    @Test
    @DisplayName("a shopper with few preferences is not truncated")
    void doesNotPadTheCategoryList() {
        hasPreferences("boots", "coats");
        given(catalogueClient.candidates(anyString(), anyList(), anyInt())).willReturn(List.of());
        given(recommendationService.rank(anyList(), anyList(), any())).willReturn(List.of());

        controller.recommendations(jwt, "acme");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> asked = ArgumentCaptor.forClass(List.class);
        verify(catalogueClient).candidates(eq("acme"), asked.capture(), anyInt());
        assertThat(asked.getValue()).containsExactly("boots", "coats");
    }
}