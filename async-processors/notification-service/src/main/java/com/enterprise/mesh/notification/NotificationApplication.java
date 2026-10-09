package com.enterprise.mesh.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Kafka-driven notification worker.
 *
 * <p>Deliberately a headless application with no embedded web server: it exposes
 * only the actuator for probes and metrics. There is no reason for this workload
 * to accept inbound HTTP, and removing the servlet stack removes the attack
 * surface entirely.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
