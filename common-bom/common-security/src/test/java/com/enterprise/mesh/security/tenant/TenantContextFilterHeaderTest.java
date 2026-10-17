package com.enterprise.mesh.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.enterprise.mesh.security.MeshSecurityProperties;

/**
 * Covers the tenant-header behaviour, which is the only part of this filter a
 * caller can actually influence.
 *
 * <p>Two distinct properties are under test. Resolution: a validated JWT claim is
 * authoritative, and the caller-supplied header is not a fallback for it.
 * Presentation: whatever {@code @RequestHeader("X-Tenant-ID")} binds downstream is
 * the resolved tenant, never what the caller sent.
 */
class TenantContextFilterHeaderTest {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final MeshSecurityProperties.Tenant properties = new MeshSecurityProperties.Tenant();
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final TenantContextFilter filter = new TenantContextFilter(properties, objectMapper);

    {
        // Mirrors what all nine services that consume common-security set in
        // application.yml. The field default is required=true, which would answer
        // every unresolvable-tenant request with 400 before the chain runs.
        properties.setRequired(false);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /** Mutable capture of what the downstream chain observed. */
    private static final class Seen {
        boolean chainRan;
        String tenantHeaderAsBound;
        String threadLocalTenant;
        HttpServletRequest request;
    }

    private Seen run(HttpServletRequest request) throws ServletException, IOException {
        Seen seen = new Seen();
        filter.doFilter(request, new MockHttpServletResponse(), capturing(seen));
        return seen;
    }

    private FilterChain capturing(Seen seen) {
        return (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            seen.chainRan = true;
            seen.request = http;
            seen.tenantHeaderAsBound = http.getHeader(TENANT_HEADER);
            seen.threadLocalTenant = TenantContext.get();
        };
    }

    /** A validated token whose tenant_id claim is {@code claim}, or absent when null. */
    private void authenticateJwtWithClaim(String claim) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "shopper-1");
        if (claim != null) {
            claims.put("tenant_id", claim);
        }
        Jwt jwt = new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"), claims);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    /** An authenticated principal that is not a JWT, e.g. HTTP Basic. */
    private void authenticateWithoutJwt() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", "pw", List.of()));
    }

    private MockHttpServletRequest requestWithTenantHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        if (value != null) {
            request.addHeader(TENANT_HEADER, value);
        }
        return request;
    }

    @Test
    @DisplayName("a JWT tenant_id claim wins over a caller-supplied X-Tenant-ID header")
    void claimBeatsHeader() throws Exception {
        authenticateJwtWithClaim("acme");

        Seen seen = run(requestWithTenantHeader("attacker-tenant"));

        assertThat(seen.chainRan).isTrue();
        assertThat(seen.threadLocalTenant).isEqualTo("acme");
        assertThat(seen.tenantHeaderAsBound).isEqualTo("acme");
    }

    @Test
    @DisplayName("a JWT carrying no tenant_id claim does NOT fall back to the caller header")
    void absentClaimIgnoresHeader() throws Exception {
        // The regression this guards: an in-mesh caller that bypasses the gateway,
        // presents a valid token with no tenant claim, and sets the header itself.
        authenticateJwtWithClaim(null);

        Seen seen = run(requestWithTenantHeader("attacker-tenant"));

        assertThat(seen.threadLocalTenant).isEqualTo("public");
        assertThat(seen.tenantHeaderAsBound).isEqualTo("public");
    }

    @Test
    @DisplayName("the resolved tenant is still visible to code enumerating header names")
    void headerVisibleToEnumeration() throws Exception {
        authenticateJwtWithClaim("acme");

        Seen seen = run(requestWithTenantHeader("attacker-tenant"));

        assertThat(Collections.list(seen.request.getHeaderNames())).contains(TENANT_HEADER);
        assertThat(Collections.list(seen.request.getHeaders(TENANT_HEADER))).containsExactly("acme");
    }

    @Test
    @DisplayName("other headers pass through untouched")
    void otherHeadersUnaffected() throws Exception {
        authenticateJwtWithClaim("acme");
        MockHttpServletRequest request = requestWithTenantHeader("attacker-tenant");
        request.addHeader("Authorization", "Bearer original-token");
        request.addHeader("Idempotency-Key", "abc-123");

        Seen seen = run(request);

        // Authorization in particular must survive: this filter re-presents one
        // header and must not perturb the credential, nor the idempotency key
        // that makes a retried payment safe to replay.
        assertThat(seen.request.getHeader("Authorization")).isEqualTo("Bearer original-token");
        assertThat(seen.request.getHeader("Idempotency-Key")).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("with no JWT principal the header is still honoured, for anonymous traffic")
    void headerHonouredWhenNoJwtPrincipal() throws Exception {
        authenticateWithoutJwt();

        Seen seen = run(requestWithTenantHeader("acme"));

        assertThat(seen.threadLocalTenant).isEqualTo("acme");
        assertThat(seen.tenantHeaderAsBound).isEqualTo("acme");
    }

    @Test
    @DisplayName("a tenant outside the allow-list is rejected with 403 and the chain never runs")
    void allowListEnforced() throws Exception {
        properties.setAllowed(List.of("acme", "globex"));
        authenticateJwtWithClaim("evilcorp");
        Seen seen = new Seen();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(requestWithTenantHeader(null), response, capturing(seen));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(seen.chainRan).isFalse();
    }

    @Test
    @DisplayName("required=true rejects a token with no resolvable tenant, ignoring the header")
    void requiredRejectsUnresolvable() throws Exception {
        properties.setRequired(true);
        authenticateJwtWithClaim(null);
        Seen seen = new Seen();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(requestWithTenantHeader("attacker-tenant"), response, capturing(seen));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(seen.chainRan).isFalse();
    }
}