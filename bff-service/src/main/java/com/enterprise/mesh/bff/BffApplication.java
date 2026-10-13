package com.enterprise.mesh.bff;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

/**
 * Backend for frontend.
 *
 * <p>The browser never sees an OAuth2 token and never speaks OAuth2. It holds one
 * opaque, {@code HttpOnly} cookie naming a server-side session in Redis; this
 * service converts that session into a short-lived bearer token on the way to the
 * {@code api-gateway}, refreshing it silently when it expires. That is what moves
 * the token out of browser-reachable storage - the XSS blast radius of a stolen
 * session cookie is bounded by the session TTL rather than by the token lifetime.
 *
 * <p>Positioned <em>in front of</em> the {@code api-gateway}, not behind it: the
 * gateway stays the single mesh entry point for every other client (mobile, partner
 * APIs), and this service is just another in-mesh caller of it. Hence the name
 * "BFF Layer (Tomcat)": it is an ordinary servlet application, because the session
 * and cookie model is inherently blocking.
 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }

    /**
     * Every expiry and TTL decision in this service reads the time through this
     * bean. Injecting a fixed clock in tests is what makes "the access token
     * expires in 40 seconds, so refresh it" a deterministic assertion instead of a
     * sleep.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}