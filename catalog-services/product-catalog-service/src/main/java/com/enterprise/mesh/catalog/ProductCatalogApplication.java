package com.enterprise.mesh.catalog;

import java.time.Duration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Read-heavy product catalogue.
 *
 * <p>The cache manager is configured explicitly rather than left to Boot's
 * default. The default uses {@code JdkSerializationRedisSerializer}, which
 * embeds Java type metadata in every value; that makes cached entries
 * unreadable by other services and breaks them across a rolling deploy when a
 * class is renamed. JSON keeps entries portable and inspectable.
 */
@SpringBootApplication
@EnableCaching
public class ProductCatalogApplication {

    /** Product detail: short TTL, since a stale price is commercially sensitive. */
    private static final Duration PRODUCT_TTL = Duration.ofMinutes(5);

    /** Category listings: safe to serve slightly stale, regenerated wholesale. */
    private static final Duration CATEGORY_TTL = Duration.ofHours(1);

    public static void main(String[] args) {
        SpringApplication.run(ProductCatalogApplication.class, args);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheManager.RedisCacheManagerBuilder builder =
                RedisCacheManager.builder(connectionFactory)
                        .cacheDefaults(baseConfig(PRODUCT_TTL))
                        .withCacheConfiguration("product", baseConfig(PRODUCT_TTL))
                        .withCacheConfiguration("category", baseConfig(CATEGORY_TTL));
        return builder.build();
    }

    private RedisCacheConfiguration baseConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // Caching nulls would pin "not found" for the full TTL after a
                // product is created, so misses are never stored.
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJacksonJsonRedisSerializer.create(b -> { })));
    }
}
