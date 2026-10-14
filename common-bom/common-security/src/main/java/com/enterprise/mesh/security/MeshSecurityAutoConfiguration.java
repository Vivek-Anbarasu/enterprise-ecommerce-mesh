package com.enterprise.mesh.security;

import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.enterprise.mesh.security.handler.ProblemDetailAccessDeniedHandler;
import com.enterprise.mesh.security.handler.ProblemDetailAuthenticationEntryPoint;
import com.enterprise.mesh.security.tenant.TenantContextFilter;

/**
 * Baseline security for every mesh service: stateless, JWT validated, method
 * security enabled, RFC 9457 error rendering, and multi-tenant resolution.
 *
 * <p>JWT verification is intentionally delegated to Spring Boot's own
 * {@code spring.security.oauth2.resourceserver.jwt.*} support, so an operator
 * can switch between {@code jwk-set-uri} (live rotation) and
 * {@code public-key-location} (PEM mounted from Key Vault via the CSI driver)
 * purely by configuration.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service may override
 * any single piece without giving up the rest of the defaults.
 */
@AutoConfiguration(after = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(MeshSecurityProperties.class)
@EnableMethodSecurity
@Slf4j
public class MeshSecurityAutoConfiguration {

    static final URI PROBLEM_BASE_URI = URI.create("https://errors.enterprise-ecommerce.com/");

    @Bean
    @ConditionalOnMissingBean
    public Converter<org.springframework.security.oauth2.jwt.Jwt, ? extends org.springframework.security.authentication.AbstractAuthenticationToken>
            meshJwtAuthenticationConverter() {

        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> converted = rolesConverter.convert(jwt);
            Collection<GrantedAuthority> authorities =
                    converted == null ? new ArrayList<>() : new ArrayList<>(converted);

            // Flat permission strings, e.g. ["orders:read"] -> PERM_orders:read
            Object permissions = jwt.getClaims().get("permissions");
            if (permissions instanceof Collection<?> collection) {
                for (Object permission : collection) {
                    authorities.add(new SimpleGrantedAuthority("PERM_" + permission));
                }
            }
            return authorities;
        });
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailAuthenticationEntryPoint meshAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new ProblemDetailAuthenticationEntryPoint(objectMapper, PROBLEM_BASE_URI);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailAccessDeniedHandler meshAccessDeniedHandler(ObjectMapper objectMapper) {
        return new ProblemDetailAccessDeniedHandler(objectMapper, PROBLEM_BASE_URI);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantContextFilter meshTenantContextFilter(MeshSecurityProperties properties, ObjectMapper objectMapper) {
        return new TenantContextFilter(properties.getTenant(), objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "meshCorsConfigurationSource")
    public CorsConfigurationSource meshCorsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Request-ID",
                "X-Tenant-ID", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of(
                "X-Request-ID", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                "X-RateLimit-Reset", "Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain meshSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtDecoder> jwtDecoderProvider,
            TenantContextFilter tenantContextFilter,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler,
            Converter<org.springframework.security.oauth2.jwt.Jwt, ? extends org.springframework.security.authentication.AbstractAuthenticationToken> jwtAuthenticationConverter,
            MeshSecurityProperties properties) throws Exception {

        if (!properties.isEnabled()) {
            log.warn("mesh.security.enabled=false - service is running WITHOUT authentication. "
                    + "This must never be the case in a deployed environment.");
        }

        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            )
            .authorizeHttpRequests(auth -> auth
                // Probes and telemetry must stay reachable for the platform to work.
                .requestMatchers("/actuator/health/**", "/actuator/info",
                                 "/actuator/prometheus", "/actuator/health/liveness",
                                 "/actuator/health/readiness").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(tenantContextFilter, UsernamePasswordAuthenticationFilter.class);

        // Only wire the resource server when a decoder actually exists, so that
        // an intentionally key-less environment (local dev, tests) still boots.
        if (jwtDecoderProvider.getIfAvailable() != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
            );
        }

        return http.build();
    }
}