package com.enterprise.mesh.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Checkout saga orchestrator and order system of record.
 *
 * <p>Coordinates inventory reservation and payment authorisation through
 * compensating actions, and guarantees "order written implies event published"
 * using a transactional outbox (see the persistence package).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
