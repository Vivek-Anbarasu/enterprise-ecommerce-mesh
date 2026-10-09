package com.enterprise.mesh.gateway.filter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Establishes the tenant for downstream calls.
 *
 * <p>Security-critical behaviour: any inbound {@code X-Tenant-ID} supplied by the
 * client is removed and then re-set <em>from the validated JWT</em>. Without the
 * removal step a caller could simply send the header themselves and impersonate
 * another tenant, because the header alone would otherwise be trusted.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantForwardingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantForwardingFilter.class);
    public static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Drop the client-supplied value unconditionally.
        ServerHttpRequest sanitised = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(TENANT_HEADER))
                .build();

        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .mapNotNull(jwt -> jwt.getClaimAsString("tenant_id"))
                .filter(tenant -> !tenant.isBlank())
                .flatMap(tenant -> {
                    ServerHttpRequest mutated = sanitised.mutate()
                            .headers(headers -> headers.set(TENANT_HEADER, tenant))
                            .build();
                    log.debug("Forwarding tenant={} to {}", tenant, sanitised.getPath());
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                // Anonymous request: forward without a tenant header. The target
                // service applies its own policy (see mesh.tenant.required).
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange.mutate().request(sanitised).build())));
    }
}
