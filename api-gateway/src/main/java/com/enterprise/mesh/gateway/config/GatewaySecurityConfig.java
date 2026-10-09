package com.enterprise.mesh.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import reactor.core.publisher.Mono;

/**
 * Reactive (WebFlux) security for the gateway.
 *
 * <p>Deliberately separate from the servlet-based {@code common-security}
 * auto-configuration: the gateway must stay fully non-blocking, so pulling in
 * servlet security would defeat the purpose.
 *
 * <p>The gateway is where a token is first validated for edge traffic.
 * Downstream services still validate independently (defence in depth), because
 * a compromised in-mesh workload could call them directly.
 */
@Configuration
@EnableWebFluxSecurity
@EnableMethodSecurity
public class GatewaySecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfig.class);

    @Bean
    public SecurityWebFilterChain gatewaySecurityFilterChain(
            ServerHttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            @org.springframework.beans.factory.annotation.Qualifier("gatewayJwtAuthenticationConverter")
            Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter) {

        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .cors(Customizer.withDefaults())
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; base-uri 'self'"))
                .hsts(hsts -> hsts
                    .includeSubdomains(true)
                    .preload(true)
                    .maxAge(Duration.ofDays(365)))
            );

        if (jwtDecoderProvider.getIfAvailable() != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint((exchange, ex) -> {
                    var response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    response.getHeaders().add("WWW-Authenticate", "Bearer");
                    return response.setComplete();
                })
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
            log.info("Gateway resource server enabled: bearer tokens are validated at the edge");
        } else {
            log.warn("Gateway starting WITHOUT JWT validation. Set "
                    + "spring.security.oauth2.resourceserver.jwt.public-key-location or "
                    + "jwk-set-uri before exposing this service to traffic.");
        }

        return http
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Browsed catalogue traffic stays anonymous; everything else
                // requires a principal.
                .pathMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**",
                              "/api/reviews/**").permitAll()
                .anyExchange().authenticated()
            )
            .build();
    }

    /**
     * Reactive security requires a {@code Converter<Jwt, Mono<AbstractAuthenticationToken>>};
     * the servlet-style converter type will not bind.
     */
    @Bean("gatewayJwtAuthenticationConverter")
    public Converter<Jwt, Mono<AbstractAuthenticationToken>> gatewayJwtAuthenticationConverter() {

        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();
        delegate.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> converted = rolesConverter.convert(jwt);
            List<GrantedAuthority> authorities =
                    converted == null ? new ArrayList<>() : new ArrayList<>(converted);

            // The tenant itself is propagated as a forwarded header by
            // TenantForwardingFilter; recording it as an authority makes it
            // available to @PreAuthorize expressions here.
            String tenant = jwt.getClaimAsString("tenant_id");
            if (tenant != null && !tenant.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("TENANT_" + tenant));
            }
            return authorities;
        });
        delegate.setPrincipalClaimName("sub");

        return jwt -> Mono.justOrEmpty((AbstractAuthenticationToken) delegate.convert(jwt));
    }

    @Bean
    public CorsConfigurationSource gatewayCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Origin patterns (not plain origins) are required because credentials
        // are allowed; the deployed allow-list is narrowed by the ingress.
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Request-ID", "X-Tenant-ID", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of(
                "X-Request-ID", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(Duration.ofHours(1).toSeconds());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
