package com.enterprise.mesh.gateway;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the gateway's behaviour when no token decoder is configured.
 *
 * <p>The failure this guards against is subtle because it is not a security
 * failure. Without a decoder the gateway rejects every bearer token, so nothing
 * is ever served to an unauthenticated caller - but the context still starts,
 * still reports healthy, and still answers probes. The observable result is a
 * gateway that serves only the anonymous catalogue, which reads as an
 * availability incident rather than a misconfiguration.
 *
 * <p>So the property worth locking down is fail-CLOSED: if this ever inverts,
 * the gateway would be routing authenticated traffic with no verification at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mesh.environment=local")
class GatewayFailsClosedWithoutJwtDecoderTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    private int statusOf(String path, String bearer) {
        AtomicInteger status = new AtomicInteger();
        var spec = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build().get().uri(path);
        if (bearer != null) {
            spec = spec.header("Authorization", bearer);
        }
        spec.exchange().expectStatus().value(status::set);
        return status.get();
    }

    @Test
    @DisplayName("no JwtDecoder bean exists without the entra profile")
    void noDecoderIsConfigured() {
        assertThat(context.getBeansOfType(JwtDecoder.class)).isEmpty();
    }

    @Test
    @DisplayName("an authenticated route rejects a request with no token")
    void anonymousRequestToAuthenticatedRouteIsRejected() {
        assertThat(statusOf("/api/orders", null)).isEqualTo(401);
    }

    @Test
    @DisplayName("an authenticated route rejects a malformed token")
    void malformedTokenIsRejected() {
        assertThat(statusOf("/api/orders", "Bearer not-a-real-token")).isEqualTo(401);
    }

    @Test
    @DisplayName("the gateway still serves anonymous catalogue browsing")
    void anonymousCatalogueRouteIsNotBlockedByTheGateway() {
        // The 503 comes from the catalogue circuit breaker, not from security: it is
        // proof the request passed authorisation and travelled to a (nonexistent)
        // backend. A 401 here would mean the edge is rejecting even what it allows.
        int status = statusOf("/api/products", null);
        assertThat(status).isNotEqualTo(401);
    }
}