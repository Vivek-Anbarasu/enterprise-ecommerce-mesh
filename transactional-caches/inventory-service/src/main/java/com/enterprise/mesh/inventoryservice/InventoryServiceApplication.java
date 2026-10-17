package com.enterprise.mesh.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Authoritative source of truth for sellable stock.
 *
 * <p>Concurrency model: stock rows carry an {@code @Version} column so that two
 * shoppers racing for the last unit produce an {@code OptimisticLockException}
 * rather than overselling. Callers treat that as "retry or reject", never as a
 * silent success.
 *
 * <p><b>No caching here, deliberately.</b> This service used to carry
 * {@code @EnableCaching} and a {@code spring.cache.type: redis} block while not
 * having a single {@code @Cacheable} or {@code @CacheEvict} anywhere, so the cache
 * layer did nothing at all. It has been removed rather than left configured and
 * inert.
 *
 * <p>It was the wrong thing to add here in the first place. Stock is the one record
 * in this mesh that must never be served stale: a cached availability answer is an
 * oversell. If caching is ever wanted, the safe form is caching the reservation
 * counters and never the authoritative row, and the TTL would need to be short
 * enough that a stale answer cannot survive a concurrent purchase.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
