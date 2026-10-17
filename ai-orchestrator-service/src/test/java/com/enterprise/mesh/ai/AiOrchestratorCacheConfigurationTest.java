package com.enterprise.mesh.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Asserts the cache this service actually ships with, rather than the one a test
 * substitutes for convenience.
 *
 * <p>This exists because the Redis configuration in {@code application.yml} was, until
 * recently, completely inert: {@code spring.cache.type: redis} was set, Redis
 * connection settings were present, and {@code @EnableCaching} was on the application
 * class - but {@code spring-boot-starter-cache} was missing, so no {@code CacheManager}
 * bean was created at all. The only symptom appeared at the first {@code @Cacheable}
 * call, as "No qualifying bean of type CacheManager". Nothing about the configuration
 * itself suggested anything was wrong.
 *
 * <p>It also pins the two properties that make this cache a privacy control rather than
 * only a latency optimisation: a TTL, so a missed eviction cannot leave a behavioural
 * profile in Redis indefinitely, and a key prefix, so this service cannot collide with
 * another service sharing the same Redis instance.
 *
 * <p>No Redis server is needed. Redis and Kafka both configure their clients lazily, so
 * the context starts without them, and the serializer assertions touch only the
 * configured serializers rather than a connection.
 */
@SpringBootTest(properties = "mesh.tenant.allow-all=true")
class AiOrchestratorCacheConfigurationTest {

    /** Must match the @CacheConfig on RecommendationService. */
    private static final String CACHE_NAME = "customer-affinity";

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("the cache manager is Redis-backed, not the default in-memory one")
    void cacheManagerIsRedisBacked() {
        assertThat(cacheManager)
                .as("spring.cache.type=redis must win over Boot's default simple cache. A "
                        + "ConcurrentMapCacheManager here means every service replica has its "
                        + "own private copy and the caching is per-pod, not shared.")
                .isInstanceOf(RedisCacheManager.class);
    }

    @Test
    @DisplayName("the affinity cache exists under the name the service declares")
    void affinityCacheExists() {
        assertThat(cacheManager.getCache(CACHE_NAME)).isNotNull();
    }

    @Test
    @DisplayName("entries have a TTL, so a missed eviction cannot retain a profile indefinitely")
    void entriesExpire() {
        // Spring Data Redis 4 replaced RedisCacheConfiguration.getTtl() with a
        // TtlFunction evaluated per write. An unset TTL yields NO_EXPIRATION - which
        // Redis reads as "keep forever" - so that is precisely the value to reject.
        Duration ttl = affinityConfiguration().getTtlFunction()
                .getTimeToLive("acme:cust-1", List.of("boots"));

        assertThat(ttl)
                .as("An unset TTL means Redis keeps the value forever and @CacheEvict is the "
                        + "only invalidation. For a behavioural profile that is a retention bug "
                        + "waiting to happen, so the bound has to exist.")
                .isNotNull()
                .isNotEqualTo(TtlFunction.NO_EXPIRATION)
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("keys are prefixed per service, so a shared Redis instance cannot collide")
    void keysArePrefixed() {
        // The effective prefix combines the configured service prefix with the cache
        // name, so assert on the leading segment rather than the whole string.
        String prefix = affinityConfiguration().getKeyPrefixFor(CACHE_NAME);

        assertThat(prefix)
                .as("Four mesh services share one Redis. Without a prefix, two of them choosing "
                        + "the same cache name would read and evict each other's entries.")
                .startsWith("ai-orchestrator-service::");
    }

    @Test
    @DisplayName("the configured serializer round-trips both a populated and an empty result")
    void serializerRoundTripsTheCachedShape() {
        // List.of() is the shape returned for every shopper with no history, which is
        // the common case and therefore the one that must survive serialisation. It is
        // backed by an immutable JDK list implementation that a plain JSON mapper will
        // not rehydrate without type information - so if this fails, every new shopper
        // breaks the second time they load the storefront.
        RedisSerializationContext.SerializationPair<Object> values =
                affinityConfiguration().getValueSerializationPair();

        for (Object original : List.of(List.of(), List.of("boots", "coats"))) {
            assertThatCode(() -> {
                // Spring Data Redis 4 works in ByteBuffer, not byte[].
                java.nio.ByteBuffer encoded = values.write(original);
                Object restored = values.read(encoded);
                assertThat(restored).isEqualTo(original);
            }).as("round trip of %s", original).doesNotThrowAnyException();
        }
    }

    private RedisCacheConfiguration affinityConfiguration() {
        // Read the configuration off the cache itself rather than off
        // getCacheConfigurations(): that map only carries caches declared up front,
        // so it is empty for a cache created on first use. The RedisCache instance
        // always reflects the configuration actually in force.
        org.springframework.cache.Cache cache = cacheManager.getCache(CACHE_NAME);
        assertThat(cache).as("cache '%s' is not registered", CACHE_NAME).isNotNull();
        assertThat(cache).isInstanceOf(org.springframework.data.redis.cache.RedisCache.class);
        return ((org.springframework.data.redis.cache.RedisCache) cache).getCacheConfiguration();
    }
}