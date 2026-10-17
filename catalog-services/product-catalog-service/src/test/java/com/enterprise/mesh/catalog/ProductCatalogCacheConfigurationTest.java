package com.enterprise.mesh.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.enterprise.mesh.catalog.service.ProductCatalogService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;

/**
 * Asserts the cache this service actually runs with, and that it matches what
 * {@link ProductCatalogService#PRODUCT_CACHE_TTL} claims.
 *
 * <p>This test exists because the constant and the thing that actually configures the
 * cache disagreed, silently. {@code PRODUCT_CACHE_TTL} was declared on the service and
 * never referenced, while {@code ProductCatalogApplication} held a private
 * {@code PRODUCT_TTL} with the same value. Two constants, one value, one of them dead —
 * and changing either would have changed nothing. The application class now uses the
 * service's constant, so there is only one, and this test pins it to the running TTL.
 *
 * <p>A second thing it pins: this service builds its own {@code RedisCacheManager}, which
 * replaces Boot's auto-configured one. That makes every {@code spring.cache.*} property
 * inert here, so the TTL and prefix have to be asserted against the running
 * configuration rather than trusted to come from {@code application.yml}.
 *
 * <p>No Redis server is needed: the cache clients connect lazily, so the context starts,
 * and these assertions touch only configuration.
 */
@SpringBootTest(properties = "mesh.tenant.allow-all=true")
class ProductCatalogCacheConfigurationTest {

    /** Must match the {@code cacheNames} on ProductCatalogService's annotations. */
    private static final String CACHE_NAME = "product";

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("the cache manager is Redis-backed, not the default in-memory one")
    void cacheManagerIsRedisBacked() {
        assertThat(cacheManager)
                .as("spring.cache.type=redis must win over Boot's default simple cache. A "
                        + "ConcurrentMapCacheManager means every replica has a private copy, so "
                        + "the cache is per-pod and evictions do not propagate.")
                .isInstanceOf(RedisCacheManager.class);
    }

    @Test
    @DisplayName("the product cache is registered under the name the service evicts")
    void productCacheExists() {
        assertThat(cacheManager.getCache(CACHE_NAME)).isNotNull();
    }

    @Test
    @DisplayName("the running TTL equals PRODUCT_CACHE_TTL")
    void runningTtlMatchesTheConstant() {
        Duration configured = productConfiguration().getTtlFunction()
                .getTimeToLive("acme:SKU-1", "cached product");

        assertThat(configured)
                .as("PRODUCT_CACHE_TTL and spring.cache.redis.time-to-live have drifted apart. "
                        + "Update both, or delete the constant and let application.yml be the "
                        + "single source of truth.")
                .isEqualTo(ProductCatalogService.PRODUCT_CACHE_TTL);
    }

    @Test
    @DisplayName("entries expire rather than living in Redis until evicted")
    void entriesExpire() {
        Duration configured = productConfiguration().getTtlFunction()
                .getTimeToLive("acme:SKU-1", "cached product");

        assertThat(configured)
                .as("An unset TTL means Redis keeps the value forever. All three mutators do "
                        + "evict, so this is a backstop rather than the primary mechanism - but "
                        + "without it a missed eviction is permanent, not temporary.")
                .isNotNull()
                .isNotEqualTo(TtlFunction.NO_EXPIRATION)
                .isPositive();
    }

    @Test
    @DisplayName("keys are prefixed per service, so a shared Redis instance cannot collide")
    void keysArePrefixed() {
        assertThat(productConfiguration().getKeyPrefixFor(CACHE_NAME))
                .as("Four mesh services share one Redis. Without a prefix, two of them choosing "
                        + "the same cache name would read and evict each other's entries.")
                .startsWith("product-catalog-service::");
    }

    @Test
    @DisplayName("the category cache keeps its own longer TTL")
    void categoryCacheHasItsOwnTtl() {
        // Two caches with deliberately different lifetimes. Asserting only the product
        // one would pass even if category had been collapsed onto it.
        Duration categoryTtl = ((RedisCache) cacheManager.getCache("category"))
                .getCacheConfiguration().getTtlFunction().getTimeToLive("acme:boots", "[]");

        assertThat(categoryTtl).isEqualTo(Duration.ofHours(1));
        assertThat(categoryTtl).isNotEqualTo(ProductCatalogService.PRODUCT_CACHE_TTL);
    }

    @Test
    @DisplayName("misses are not cached, so creating a product is visible immediately")
    void nullsAreNotCached() {
        assertThat(productConfiguration().getAllowCacheNullValues())
                .as("Caching null would pin 'not found' for the full TTL after a product is "
                        + "created, so a new product would stay invisible for five minutes.")
                .isFalse();
    }

    private RedisCacheConfiguration productConfiguration() {
        // Read the configuration off the cache itself: getCacheConfigurations() only
        // carries caches declared up front, so it is empty for one created on first use.
        Cache cache = cacheManager.getCache(CACHE_NAME);
        assertThat(cache).as("cache '%s' is not registered", CACHE_NAME).isNotNull();
        assertThat(cache).isInstanceOf(RedisCache.class);
        return ((RedisCache) cache).getCacheConfiguration();
    }
}