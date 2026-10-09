package com.enterprise.mesh.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Personalisation and conversational search.
 *
 * <p>Non-critical tier: the gateway circuit-breaks and degrades to standard
 * browsing when this service is unavailable, so a failure here must never block
 * a purchase.
 */
@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan
public class AiOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiOrchestratorApplication.class, args);
    }
}
