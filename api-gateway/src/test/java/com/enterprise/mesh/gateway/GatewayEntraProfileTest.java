package com.enterprise.mesh.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.bulkhead.BulkheadRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway booting with Entra as its token issuer.
 *
 * <p>Pins the wiring, not the cryptography: that the profile activates, the
 * properties resolve, and a decoder is produced. It deliberately does NOT
 * exercise {@code decode()} end to end - that would require fetching the real
 * JWKS from login.microsoftonline.com, so the assertion would be about network
 * reachability rather than about configuration.
 *
 * <p>What this does pin is the property that actually matters and is easy to lose:
 * that {@code issuer-uri} is configured at all. Entra signs every tenant's tokens
 * with a shared key set, so a decoder built from {@code jwk-set-uri} alone would
 * verify the signature perfectly and still accept a token minted by a completely
 * different directory - and {@code GatewaySecurityConfig} turns that token's
 * {@code tenant_id} claim into a {@code TENANT_*} authority. If someone ever
 * deletes the {@code issuer-uri} line thinking the JWKS pin is enough, this fails.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "mesh.environment=local",
        "ENTRA_TENANT_ID=11111111-1111-1111-1111-111111111111",
        "spring.profiles.active=entra"
})
class GatewayEntraProfileTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("the entra profile produces a JwtDecoder")
    void decoderIsConfigured() {
        assertThat(jwtDecoder).isNotNull();
    }

    @Test
    @DisplayName("the issuer is pinned to this directory, not discovered")
    void issuerIsPinned() {
        assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .isEqualTo("https://login.microsoftonline.com/" + TENANT + "/v2.0");
    }

    @Test
    @DisplayName("the signing keys are pinned too, so startup needs no network")
    void jwkSetIsPinned() {
        assertThat(environment.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"))
                .isEqualTo("https://login.microsoftonline.com/" + TENANT + "/discovery/v2.0/keys");
    }

    @Test
    @DisplayName("the gateway is not configured as an OAuth2 client")
    void gatewayIsAResourceServerOnly() {
        // The gateway validates tokens other services minted; it never obtains one.
        // A client-id here would mean the BFF and the gateway share a registration,
        // so compromising the gateway's config could also mint tokens.
        assertThat(environment.getProperty("spring.security.oauth2.client.registration.entra.client-id"))
                .isNull();
    }

    @Test
    @DisplayName("the bulkheads still resolve alongside the security config")
    void bulkheadsStillWork() {
        // Cheap guard against the new profile reordering or shadowing beans; the
        // routes only build at all if both configurations coexist.
        assertThat(bulkheadRegistry.find("aiBulkhead")).isPresent();
        assertThat(bulkheadRegistry.find("paymentBulkhead")).isPresent();
    }
}