package com.enterprise.mesh.gateway.config;

import java.nio.charset.StandardCharsets;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Mono;

import com.enterprise.mesh.gateway.filter.TenantForwardingFilter;

/**
 * Rate-limit key resolvers.
 *
 * <p>Rate limiting is scoped per tenant rather than per IP. A single corporate
 * NAT or mobile carrier CGNAT can put thousands of unrelated shoppers behind one
 * source address, so IP-based limits punish innocent customers and let one
 * abusive tenant exhaust the whole cluster's budget.
 */
@Configuration
public class RateLimitKeyResolverConfig {

    /**
     * Default key: tenant when authenticated, otherwise the remote address.
     * Referenced from {@code application.yml} as {@code @tenantKeyResolver}.
     */
    @Bean
    @Primary
    public KeyResolver tenantKeyResolver() {
        return exchange -> {
            String tenant = exchange.getRequest().getHeaders().getFirst(TenantForwardingFilter.TENANT_HEADER);
            if (tenant != null && !tenant.isBlank()) {
                return Mono.just("tenant:" + tenant);
            }
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            String address = (forwardedFor != null && !forwardedFor.isBlank())
                    ? forwardedFor.split(",")[0].trim()
                    : exchange.getRequest().getRemoteAddress() == null
                        ? "unknown"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            return Mono.just("ip:" + address);
        };
    }

    /** Keyed on the authenticated subject, for per-customer limits. */
    @Bean
    public KeyResolver customerKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> principal.getName())
                .defaultIfEmpty("anonymous")
                .map(name -> "customer:" + name);
    }

    /** Keyed on the called path, so one noisy endpoint cannot starve others. */
    @Bean
    public KeyResolver pathKeyResolver() {
        return exchange -> Mono.just(
                "path:" + Integer.toHexString(exchange.getRequest().getPath().value().hashCode()));
    }
}
