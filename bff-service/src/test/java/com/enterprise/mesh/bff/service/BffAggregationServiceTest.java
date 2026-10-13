package com.enterprise.mesh.bff.service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.JsonNode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.enterprise.mesh.bff.api.BffViews.DashboardView;
import com.enterprise.mesh.bff.api.BffViews.SectionStatus;
import com.enterprise.mesh.bff.api.BffViews.StorefrontView;
import com.enterprise.mesh.bff.client.DownstreamException;
import com.enterprise.mesh.bff.client.MeshApiClient;
import com.enterprise.mesh.bff.config.BffProperties;
import com.enterprise.mesh.bff.session.SessionTokenService;
import com.enterprise.mesh.bff.session.SessionTokens;
import com.enterprise.mesh.observability.BusinessMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Partial-failure behaviour, which is the reason the aggregation exists at all: a
 * shopper should still see their cart when the recommendation service is down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BffAggregationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");

    @Mock
    private MeshApiClient meshApiClient;

    @Mock
    private SessionTokenService sessionTokenService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private final ObjectMapperHolder holder = new ObjectMapperHolder();
    private BffAggregationService aggregationService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        BffProperties properties = new BffProperties();
        properties.setAggregationTimeout(java.time.Duration.ofSeconds(5));

        executor = Executors.newVirtualThreadPerTaskExecutor();
        aggregationService = new BffAggregationService(
                meshApiClient, new BffViewMapper(), sessionTokenService, properties,
                new BusinessMetrics(new SimpleMeterRegistry()), executor);

        when(sessionTokenService.resolve(any(), any(), any()))
                .thenReturn(new SessionTokens("access", "user-42", "public",
                        NOW.plusSeconds(3600), false, true));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private Authentication authentication() {
        return new UsernamePasswordAuthenticationToken("user-42", "n/a", java.util.List.of());
    }

    // --------------------------------------------------------------- storefront

    @Test
    @DisplayName("the home page derives its category breakdown from the product page it already fetched")
    void derivesCategoriesFromTheSameFetch() {
        when(meshApiClient.get(anyString())).thenReturn(holder.read("""
                { "content": [
                    { "sku": "A", "category": "audio" },
                    { "sku": "B", "category": "audio" },
                    { "sku": "C", "category": "kitchen" }
                ] }
                """));

        StorefrontView view = aggregationService.storefront();

        // One call, not four: the shopper sees this page before signing in and is
        // not paying for a fan-out they cannot see the value of.
        org.mockito.Mockito.verify(meshApiClient, org.mockito.Mockito.times(1)).get(anyString());
        assertThat(view.categories())
                .extracting("name")
                .containsExactly("audio", "kitchen");
        assertThat(view.categories().getFirst().productCount()).isEqualTo(2);
        assertThat(view.sections()).singleElement()
                .satisfies(section -> assertThat(section.available()).isTrue());
    }

    @Test
    @DisplayName("a dead catalog yields an empty page and a failed section, not a 502")
    void degradesWhenCatalogIsDown() {
        when(meshApiClient.get(anyString()))
                .thenThrow(DownstreamException.from(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "/api/products", "The service is temporarily unavailable"));

        StorefrontView view = aggregationService.storefront();

        assertThat(view.featuredProducts()).isEmpty();
        assertThat(view.categories()).isEmpty();
        assertThat(view.sections()).singleElement().satisfies(section -> {
            assertThat(section.section()).isEqualTo("catalog");
            assertThat(section.available()).isFalse();
            assertThat(section.detail()).isEqualTo("The service is temporarily unavailable");
        });
    }

    // ---------------------------------------------------------------- dashboard

    @Test
    @DisplayName("a healthy dashboard assembles all four sections")
    void assemblesFullDashboard() {
        givenAllSectionsHealthy();

        DashboardView view = aggregationService.dashboard(request, response, authentication());

        assertThat(view.partial()).isFalse();
        assertThat(view.profile().displayName()).isEqualTo("Sam");
        assertThat(view.cart().itemCount()).isEqualTo(2);
        assertThat(view.recentOrders()).hasSize(1);
        assertThat(view.recommendations()).hasSize(1);
        assertThat(view.preferredCategories()).containsExactly("audio");
        assertThat(view.sections()).allMatch(SectionStatus::available);
    }

    @Test
    @DisplayName("one failing service costs one widget; the rest of the page still renders")
    void degradesSectionBySection() {
        givenAllSectionsHealthy();
        // The recommender is the section most likely to be missing in a real
        // deployment - a new shopper has no history, and the service may be young.
        when(meshApiClient.get(eq("/api/ai/recommendations"), any()))
                .thenThrow(DownstreamException.from(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "/api/ai/recommendations", "The service is temporarily unavailable"));

        DashboardView view = aggregationService.dashboard(request, response, authentication());

        assertThat(view.partial()).isTrue();
        // The shopper still gets everything that mattered.
        assertThat(view.profile().displayName()).isEqualTo("Sam");
        assertThat(view.cart().itemCount()).isEqualTo(2);
        assertThat(view.recentOrders()).hasSize(1);
        assertThat(view.recommendations()).isEmpty();

        assertThat(view.sections())
                .filteredOn(section -> section.section().equals("recommendations"))
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.available()).isFalse();
                    assertThat(section.detail()).isEqualTo("The service is temporarily unavailable");
                });
        assertThat(view.sections())
                .filteredOn(section -> !section.section().equals("recommendations"))
                .allMatch(SectionStatus::available);
    }

    @Test
    @DisplayName("an unexpected internal error never leaks its message to the browser")
    void doesNotLeakInternalFailureDetail() {
        givenAllSectionsHealthy();
        when(meshApiClient.get(eq("/api/cart"), any()))
                .thenThrow(new IllegalStateException("connection pool exhausted at com.internal.Foo.bar(Foo.java:42)"));

        DashboardView view = aggregationService.dashboard(request, response, authentication());

        assertThat(view.cart()).isNull();
        assertThat(view.sections())
                .filteredOn(section -> section.section().equals("cart"))
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.available()).isFalse();
                    assertThat(section.detail())
                            .isEqualTo("The service is temporarily unavailable")
                            .doesNotContain("com.internal");
                });
    }

    @Test
    @DisplayName("sections run concurrently rather than in sequence")
    void fansOutConcurrently() throws Exception {
        givenAllSectionsHealthy();
        // Every section blocks until all four are waiting, which can only happen if
        // they were dispatched in parallel. A sequential implementation deadlocks
        // here and the test times out instead of hanging forever, because the
        // futures settle on the aggregation budget.
        var barrier = new java.util.concurrent.CyclicBarrier(4);
        when(meshApiClient.get(anyString(), any())).thenAnswer(invocation -> {
            barrier.await(3, java.util.concurrent.TimeUnit.SECONDS);
            return holder.read("{}");
        });

        DashboardView view = aggregationService.dashboard(request, response, authentication());

        assertThat(view.sections()).hasSize(4);
    }

    private void givenAllSectionsHealthy() {
        JsonNode profile = holder.read("""
                { "id": "c1", "displayName": "Sam", "email": "sam@example.test" }
                """);
        JsonNode cart = holder.read("""
                { "customerId": "user-42", "lines": [ {"sku":"A","quantity":2} ],
                  "subtotal": 20.00, "currency": "USD" }
                """);
        JsonNode orders = holder.read("""
                { "content": [ { "id": "o1", "status": "PAID", "totalAmount": 20.00, "currency": "USD" } ] }
                """);
        JsonNode recommendations = holder.read("""
                { "preferredCategories": ["audio"],
                  "recommendations": [ {"sku":"SKU-9","category":"audio","score":0.9} ] }
                """);

        when(meshApiClient.get(eq("/api/customers/me"), any())).thenReturn(profile);
        when(meshApiClient.get(eq("/api/cart"), any())).thenReturn(cart);
        // Built from the same constant the service uses, so a change to the page
        // size breaks this stub loudly instead of silently returning null - which
        // is exactly how a missing token argument on this call once went unnoticed.
        when(meshApiClient.get(eq("/api/orders?page=0&size=" + BffViewMapper.RECENT_ORDER_LIMIT), any()))
                .thenReturn(orders);
        when(meshApiClient.get(eq("/api/ai/recommendations"), any())).thenReturn(recommendations);
    }

    private static final class ObjectMapperHolder {

        private final tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

        JsonNode read(String json) {
            return mapper.readTree(json);
        }
    }
}
