package com.enterprise.mesh.gateway.config;

import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import com.enterprise.mesh.gateway.filter.TenantForwardingFilter;
import reactor.core.publisher.Mono;

/**
 * Rate-limit key resolvers.
 *
 * <p>Rate limiting is scoped per tenant rather than per IP.
 * Anonymous traffic (which AGC has already rate-limited at the edge)
 * falls back to Spring's natively extracted, AGC-verified remote address.
 */
@Configuration
public class RateLimitKeyResolverConfig {

    /**
     * Default key: tenant when authenticated, otherwise the client address.
     * Referenced from {@code application.yml} as {@code @tenantKeyResolver}.
     */
    @Bean
    @Primary
    public KeyResolver tenantKeyResolver() {
        return exchange -> {
            // 1. Primary multi-tenant protection
            String tenant = exchange.getRequest().getHeaders().getFirst(TenantForwardingFilter.TENANT_HEADER);
            if (StringUtils.hasText(tenant)) {
                return Mono.just("tenant:" + tenant);
            }

            // 2. Fallback: Trust Spring's automatically extracted, AGC-verified remote address
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String address = (remote != null && remote.getAddress() != null)
                    ? remote.getAddress().getHostAddress()
                    : "unknown";

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
