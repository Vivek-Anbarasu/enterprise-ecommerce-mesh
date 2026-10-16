package com.enterprise.mesh.gateway.config;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate-limit key resolution.
 *
 * <p>The key is the tenant when the request carries one, and the socket peer
 * otherwise. The peer is deliberately the ONLY source for the anonymous branch,
 * and that is the security property worth pinning.
 *
 * <p>Keying on {@code X-Forwarded-For} was the obvious alternative and it was
 * wrong: every proxy appends rather than overwrites, so a client can prepend a
 * fresh value per request and land in a different rate-limit bucket every time.
 * Nothing errors, the limiter simply does not limit. The socket peer is not
 * attacker-controllable, so this branch cannot be steered from outside.
 *
 * <p>The cost is that anonymous traffic behind Application Gateway shares AGC's
 * address and therefore one bucket. That is accepted, on the basis that the edge
 * is where per-client limits belong; see the note on
 * {@link #anonymousTrafficSharesOneBucketByDesign()} so it is not mistaken for a
 * bug later.
 */
class RateLimitKeyResolverConfigTest {

    /** Socket peer of the test exchange, standing in for Application Gateway. */
    private static final String SOCKET_PEER = "10.9.9.9";

    private static final RateLimitKeyResolverConfig CONFIG = new RateLimitKeyResolverConfig();

    private static String keyOf(KeyResolver resolver, ServerWebExchange exchange) {
        AtomicReference<String> key = new AtomicReference<>();
        resolver.resolve(exchange).subscribe(key::set);
        assertThat(key.get()).isNotNull();
        return key.get();
    }

    private static ServerWebExchange anonymous(String forwardedFor, String remoteAddress) {
        MockServerHttpRequest.BaseBuilder<?> request =
                MockServerHttpRequest.get("/api/products");
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        if (remoteAddress != null) {
            request = request.remoteAddress(new InetSocketAddress(remoteAddress, 443));
        }
        return MockServerWebExchange.from(request.build());
    }

    // ---------------------------------------------------------------- tenant keying

    @Test
    @DisplayName("a request carrying a tenant is keyed on the tenant")
    void tenantHeaderWins() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products")
                        .header("X-Tenant-ID", "acme")
                        .remoteAddress(new InetSocketAddress(SOCKET_PEER, 443)));

        assertThat(keyOf(CONFIG.tenantKeyResolver(), exchange)).isEqualTo("tenant:acme");
    }

    @Test
    @DisplayName("the tenant is keyed even when a forwarded header is present")
    void tenantBeatsForwardedHeader() {
        // The tenant is the real accounting unit. A caller-supplied X-Forwarded-For
        // must not be able to move a request into a different bucket.
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products")
                        .header("X-Tenant-ID", "acme")
                        .header("X-Forwarded-For", "6.6.6.6")
                        .remoteAddress(new InetSocketAddress(SOCKET_PEER, 443)));

        assertThat(keyOf(CONFIG.tenantKeyResolver(), exchange)).isEqualTo("tenant:acme");
    }

    @Test
    @DisplayName("a blank tenant header falls back to the socket peer")
    void blankTenantHeaderFallsBack() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products")
                        .header("X-Tenant-ID", "   ")
                        .remoteAddress(new InetSocketAddress(SOCKET_PEER, 443)));

        assertThat(keyOf(CONFIG.tenantKeyResolver(), exchange)).isEqualTo("ip:" + SOCKET_PEER);
    }

    // ---------------------------------------------------------------- socket-peer keying

    @Test
    @DisplayName("anonymous traffic is keyed on the socket peer")
    void anonymousUsesSocketPeer() {
        assertThat(keyOf(CONFIG.tenantKeyResolver(), anonymous(null, SOCKET_PEER)))
                .isEqualTo("ip:" + SOCKET_PEER);
    }

    @Test
    @DisplayName("a spoofed X-Forwarded-For cannot choose the bucket")
    void spoofedForwardedHeaderIsIgnored() {
        // The regression this whole design exists to prevent. A client varying the
        // header gets the same key every time, so the limit still applies.
        String first = keyOf(CONFIG.tenantKeyResolver(), anonymous("6.6.6.6", SOCKET_PEER));
        String second = keyOf(CONFIG.tenantKeyResolver(), anonymous("9.9.9.9", SOCKET_PEER));
        String third = keyOf(CONFIG.tenantKeyResolver(), anonymous("evil.example.com", SOCKET_PEER));

        assertThat(first).isEqualTo("ip:" + SOCKET_PEER);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    @DisplayName("a long spoofed chain is ignored just the same")
    void spoofedChainIsIgnored() {
        String key = keyOf(CONFIG.tenantKeyResolver(),
                anonymous("1.1.1.1, 2.2.2.2, 3.3.3.3, 198.51.100.9", SOCKET_PEER));

        assertThat(key).isEqualTo("ip:" + SOCKET_PEER);
    }

    @Test
    @DisplayName("a request with no remote address falls back to a shared unknown key")
    void missingRemoteAddressFallsBackToUnknown() {
        // Must not throw. MockServerWebExchange has no peer unless one is set, and
        // a null peer at the edge is a real possibility on an internal call.
        assertThat(keyOf(CONFIG.tenantKeyResolver(), anonymous(null, null))).isEqualTo("ip:unknown");
    }

    @Test
    @DisplayName("anonymous traffic shares one bucket by design")
    void anonymousTrafficSharesOneBucketByDesign() {
        // Documented consequence of keying on the socket peer: behind Application
        // Gateway every anonymous shopper shares AGC's address and so one bucket.
        // Accepted deliberately - per-client limiting belongs at the edge, and the
        // alternative (X-Forwarded-For) is steerable by the caller.
        //
        // If this assertion ever fails, someone has reintroduced a per-client key.
        // That may be a good change, but it must be a conscious one: confirm the
        // source of that address is not caller-controlled before shipping it.
        String key = keyOf(CONFIG.tenantKeyResolver(), anonymous("203.0.113.99", SOCKET_PEER));

        assertThat(key).isEqualTo(keyOf(CONFIG.tenantKeyResolver(), anonymous(null, SOCKET_PEER)));
    }

    // ---------------------------------------------------------------- other resolvers

    @Test
    @DisplayName("the customer resolver keys on the authenticated subject")
    void customerResolverUsesPrincipal() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/cart").build());
        exchange = exchange.mutate()
                .principal(Mono.just(new UsernamePasswordAuthenticationToken(
                        "alice", null, List.of())))
                .build();

        assertThat(keyOf(CONFIG.customerKeyResolver(), exchange)).isEqualTo("customer:alice");
    }

    @Test
    @DisplayName("the customer resolver degrades to anonymous without a principal")
    void customerResolverFallsBackToAnonymous() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/cart").build());

        assertThat(keyOf(CONFIG.customerKeyResolver(), exchange)).isEqualTo("customer:anonymous");
    }

    @Test
    @DisplayName("the path resolver is stable per path and differs across paths")
    void pathResolverIsStableAndDistinct() {
        String cart = keyOf(CONFIG.pathKeyResolver(),
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/cart").build()));
        String cartAgain = keyOf(CONFIG.pathKeyResolver(),
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/cart").build()));
        String orders = keyOf(CONFIG.pathKeyResolver(),
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/orders").build()));

        assertThat(cart).isEqualTo(cartAgain).startsWith("path:");
        assertThat(cart).isNotEqualTo(orders);
    }
}