package com.enterprise.mesh.catalog;

import java.time.Duration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.enterprise.mesh.catalog.service.ProductCatalogService;

/**
 * Read-heavy product catalogue.
 *
 * <p>The cache manager is configured explicitly rather than left to Boot's
 * default. The default uses {@code JdkSerializationRedisSerializer}, which
 * embeds Java type metadata in every value; that makes cached entries
 * unreadable by other services and breaks them across a rolling deploy when a
 * class is renamed. JSON keeps entries portable and inspectable.
 *
 * <p><b>Declaring this bean makes every {@code spring.cache.*} property inert
 * for this service</b> - TTL, key prefix and cache names alike. That is worth
 * knowing before adding any of them to {@code application.yml} expecting them to
 * work: they will bind, they will be readable, and they will change nothing.
 * Everything this cache needs is set here, where the reason for it is visible.
 */
@SpringBootApplication
@EnableCaching
public class ProductCatalogApplication {

    /**
     * Namespaces this service's keys in the shared Redis instance.
     *
     * <p>Four mesh services point at one Redis. Without a per-service prefix, two of
     * them using the same cache name would read and evict each other's entries, and
     * the failure would look like a mysterious cache miss rather than a collision.
     */
    private static final String KEY_PREFIX = "product-catalog-service::";

    /** Category listings: safe to serve slightly stale, regenerated wholesale. */
    private static final Duration CATEGORY_TTL = Duration.ofHours(1);

    public static void main(String[] args) {
        SpringApplication.run(ProductCatalogApplication.class, args);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // The product TTL is ProductCatalogService.PRODUCT_CACHE_TTL, not a second
        // constant. It used to be duplicated here as a private PRODUCT_TTL with the
        // same value, while the copy on the service sat there unreferenced - so
        // changing one would have silently changed nothing. One constant, owned by
        // the class whose annotations name the cache.
        Duration productTtl = ProductCatalogService.PRODUCT_CACHE_TTL;

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig(productTtl))
                .withCacheConfiguration("product", baseConfig(productTtl))
                .withCacheConfiguration("category", baseConfig(CATEGORY_TTL))
                .build();
    }

    private RedisCacheConfiguration baseConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // Caching nulls would pin "not found" for the full TTL after a
                // product is created, so misses are never stored.
                .disableCachingNullValues()
                .computePrefixWith(CacheKeyPrefix.prefixed(KEY_PREFIX))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJacksonJsonRedisSerializer.create(b -> { })));
    }
}
