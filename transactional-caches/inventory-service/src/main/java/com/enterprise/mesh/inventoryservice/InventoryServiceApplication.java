package com.enterprise.mesh.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Authoritative source of truth for sellable stock.
 *
 * <p>Concurrency model: stock rows carry an {@code @Version} column so that two
 * shoppers racing for the last unit produce an {@code OptimisticLockException}
 * rather than overselling. Callers treat that as "retry or reject", never as a
 * silent success.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
