package com.enterprise.mesh.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import com.enterprise.mesh.ai.domain.ProductAffinity;
import com.enterprise.mesh.ai.repository.ProductAffinityRepository;
import com.enterprise.mesh.ai.service.RecommendationService.InteractionType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Behavioural contract for the Redis-backed affinity cache.
 *
 * <p>Runs against an in-memory {@code CacheManager} ({@code spring.cache.type=simple})
 * rather than Redis. The point of every assertion here is the caching advice - whether
 * a second call reaches Mongo, whether a key is tenant-scoped, whether a mutation
 * evicts - and all of that is decided by the proxy, not by the backing store. Redis
 * only supplies the map. The production configuration is asserted separately in
 * {@code AiOrchestratorCacheConfigurationTest}, which checks the bean is genuinely
 * Redis-backed and genuinely has a TTL.
 *
 * <p>The service is always called through the injected proxy, never as a plain object:
 * constructing {@code new RecommendationService(repo)} would bypass the caching
 * advice entirely and every one of these tests would pass vacuously.
 */
@SpringBootTest(properties = {
        "spring.cache.type=simple",
        "mesh.tenant.allow-all=true"
})
class RecommendationServiceCacheTest {

    @MockitoBean
    private ProductAffinityRepository affinityRepository;

    @Autowired
    private RecommendationService recommendationService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void resetCache() {
        // A shared Redis instance outlives a single test, and so does the in-memory
        // one. Clearing per test is what keeps the eviction assertions honest.
        cacheManager.getCache("customer-affinity").clear();
        org.mockito.Mockito.reset(affinityRepository);
    }

    private ProductAffinity affinity(String customerId, String tenantId, String category, double weight) {
        ProductAffinity affinity = new ProductAffinity(
                java.util.UUID.randomUUID().toString(), customerId, tenantId, category);
        affinity.recordInteraction(weight);
        return affinity;
    }

    @Test
    @DisplayName("a repeated read for the same shopper does not hit Mongo again")
    void secondReadIsServedFromCache() {
        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme"))
                .willReturn(List.of(
                        affinity("cust-1", "acme", "boots", 1d),
                        affinity("cust-1", "acme", "bags", 1d)));

        List<String> first = recommendationService.preferredCategories("cust-1", "acme");
        List<String> second = recommendationService.preferredCategories("cust-1", "acme");
        List<String> third = recommendationService.preferredCategories("cust-1", "acme");

        assertThat(first).isNotEmpty();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        // The whole point: three storefront home page loads, one database round trip.
        verify(affinityRepository, times(1)).findByCustomerIdAndTenantId("cust-1", "acme");
    }

    @Test
    @DisplayName("the cache key is tenant-scoped, so the same shopper id in two tenants is two entries")
    void keyIsTenantScoped() {
        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme"))
                .willReturn(List.of(affinity("cust-1", "acme", "boots", 1d)));
        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "globex"))
                .willReturn(List.of(affinity("cust-1", "globex", "kitchen", 1d)));

        assertThat(recommendationService.preferredCategories("cust-1", "acme")).containsExactly("boots");
        assertThat(recommendationService.preferredCategories("cust-1", "globex")).containsExactly("kitchen");

        // The failure this prevents: one tenant's cached preferences served to another
        // because the shopper id alone was the key.
        verify(affinityRepository).findByCustomerIdAndTenantId("cust-1", "acme");
        verify(affinityRepository).findByCustomerIdAndTenantId("cust-1", "globex");
    }

    @Test
    @DisplayName("an empty preference list is cached, because a new shopper is the common case")
    void emptyResultIsCached() {
        given(affinityRepository.findByCustomerIdAndTenantId("new-shopper", "acme")).willReturn(List.of());

        assertThat(recommendationService.preferredCategories("new-shopper", "acme")).isEmpty();
        assertThat(recommendationService.preferredCategories("new-shopper", "acme")).isEmpty();

        verify(affinityRepository, times(1)).findByCustomerIdAndTenantId("new-shopper", "acme");
    }

    @Test
    @DisplayName("recording an interaction evicts, so the next read recomputes")
    void recordInteractionEvicts() {
        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme"))
                .willReturn(List.of(affinity("cust-1", "acme", "boots", 1d)));

        assertThat(recommendationService.preferredCategories("cust-1", "acme")).containsExactly("boots");
        verify(affinityRepository, times(1)).findByCustomerIdAndTenantId("cust-1", "acme");

        recommendationService.recordInteraction("cust-1", "acme", "coats", InteractionType.PURCHASE);

        // recordInteraction looks the existing affinity up before saving it, so it
        // queries the repository too. Count only from here: this assertion is about
        // what the read path does next, not about the write's own lookup.
        clearInvocations(affinityRepository);

        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme"))
                .willReturn(List.of(
                        affinity("cust-1", "acme", "boots", 1d),
                        affinity("cust-1", "acme", "coats", 8d)));

        assertThat(recommendationService.preferredCategories("cust-1", "acme"))
                .containsExactly("coats", "boots");
        // Exactly one recompute proves the entry was evicted rather than served stale.
        verify(affinityRepository, times(1)).findByCustomerIdAndTenantId("cust-1", "acme");
    }

    @Test
    @DisplayName("eviction is per shopper, so one shopper's interaction does not flush another's cache")
    void recordInteractionEvictsOnlyThatShopper() {
        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme"))
                .willReturn(List.of(affinity("cust-1", "acme", "boots", 1d)));
        given(affinityRepository.findByCustomerIdAndTenantId("cust-2", "acme"))
                .willReturn(List.of(affinity("cust-2", "acme", "kitchen", 1d)));

        recommendationService.preferredCategories("cust-1", "acme");
        recommendationService.preferredCategories("cust-2", "acme");

        recommendationService.recordInteraction("cust-1", "acme", "coats", InteractionType.VIEW);
        clearInvocations(affinityRepository);

        recommendationService.preferredCategories("cust-1", "acme");
        recommendationService.preferredCategories("cust-2", "acme");
        recommendationService.preferredCategories("cust-2", "acme");

        // cust-2 was served from cache across three reads; only cust-1 recomputed. A
        // cache-wide flush here would turn every purchase into a Mongo round trip for
        // every other shopper on the site.
        verify(affinityRepository, times(1)).findByCustomerIdAndTenantId("cust-1", "acme");
        verify(affinityRepository, never()).findByCustomerIdAndTenantId("cust-2", "acme");
    }

    @Test
    @DisplayName("forget evicts the erased profile, so erasure survives the cache")
    void forgetEvicts() {
        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme"))
                .willReturn(List.of(affinity("cust-1", "acme", "boots", 1d)));

        assertThat(recommendationService.preferredCategories("cust-1", "acme")).containsExactly("boots");

        recommendationService.forget("cust-1", "acme");
        // Assert the delete before resetting: clearInvocations below would erase the
        // record of it along with the read path's.
        verify(affinityRepository).deleteByCustomerIdAndTenantId("cust-1", "acme");
        clearInvocations(affinityRepository);

        given(affinityRepository.findByCustomerIdAndTenantId("cust-1", "acme")).willReturn(List.of());
        assertThat(recommendationService.preferredCategories("cust-1", "acme")).isEmpty();

        // The failure this prevents: rows deleted, cache entry left populated, and the
        // storefront keeps serving behaviour the shopper asked to have erased.
        verify(affinityRepository, times(1)).findByCustomerIdAndTenantId("cust-1", "acme");
    }

    @Test
    @DisplayName("a blank interaction is a no-op and never reads the database")
    void blankInteractionIsANoOp() {
        recommendationService.recordInteraction("cust-1", "acme", "  ", InteractionType.VIEW);

        verify(affinityRepository, never()).findByCustomerIdAndTenantId(anyString(), anyString());
        verifyNoInteractions(affinityRepository);
    }

    @Test
    @DisplayName("an anonymous read never reaches the database")
    void anonymousReadSkipsTheDatabase() {
        // The storefront's unauthenticated home page. With no customer there is no
        // profile to look up, so the cache should not even be asked to populate from
        // a query.
        assertThat(recommendationService.preferredCategories(null, "public")).isEmpty();

        verify(affinityRepository, never()).findByCustomerIdAndTenantId(any(), any());
    }
}