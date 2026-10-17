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
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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

    /**
     * Entra ID as the issuer, for the services behind the gateway that receive a
     * token the gateway has already accepted.
     *
     * <p>The gateway and the BFF declare their own {@code application-entra.yml},
     * but a downstream service has no reason to repeat the issuer block: it is
     * the same directory, so it is the same keys and the same {@code iss} value.
     * Deriving it from {@code ENTRA_TENANT_ID} here means there is exactly one
     * place to get it wrong, and a service picks it up by activating {@code entra}
     * rather than by adding a file that could drift.
     *
     * <p>Deliberately {@code @ConditionalOnMissingBean}, and deliberately ordered
     * after Boot's resource server auto-configuration. An operator who wants a
     * different key source - a PEM mounted from Key Vault, say - sets
     * {@code spring.security.oauth2.resourceserver.jwt.public-key-location} and
     * Boot's own decoder wins, exactly as it would without this class. This is a
     * default, not an override.
     */
    @Bean
    @Profile("entra")
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder meshEntraJwtDecoder(Environment environment) {
        String tenantId = requireTenantId(environment);

        String issuer = "https://login.microsoftonline.com/" + tenantId + "/v2.0";
        String jwkSetUri = "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";

        log.info("Resource server configured for Entra directory {}: forwarded bearer tokens "
                + "are validated against issuer '{}'.", tenantId, issuer);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // Both halves are needed and they do different jobs. The JWK set supplies
        // the signing keys; the issuer validator is what rejects a token whose
        // `iss` is some other directory. Validating the signature alone is the
        // dangerous shortcut, because Entra signs every tenant's tokens with a
        // shared key set - so a perfectly valid token minted by a stranger's
        // Azure AD directory would be accepted, and TenantContextFilter would
        // turn its tenant_id claim into a TENANT_* authority for this mesh.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /**
     * @see #requireTenantId(Environment)
     */
    private String requireTenantId(Environment environment) {
        String tenantId = environment.getProperty("ENTRA_TENANT_ID");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    "The 'entra' profile is active but ENTRA_TENANT_ID is unset. The issuer URL is "
                            + "built from it, so an empty value produces a valid-looking URL pointing "
                            + "at the wrong directory: this service would start, pass its health checks, "
                            + "and reject every real token as foreign. Set the directory (tenant) id, "
                            + "e.g. with --set services.<service>.extraEnv.ENTRA_TENANT_ID=<directory-id>.");
        }
        if (tenantId.startsWith("REPLACE_")) {
            throw new IllegalStateException(
                    "ENTRA_TENANT_ID is still the placeholder value '" + tenantId + "'. Replace it "
                            + "with the directory (tenant) id from the Entra app registration. It must "
                            + "be the SAME tenant as the api-gateway and bff-service, because the "
                            + "gateway pins its issuer to exactly that directory.");
        }
        return tenantId;
    }

    /**
     * Refuses to start when a service that should be validating tokens has no
     * decoder to validate them with.
     *
     * <p>Without a decoder the filter chain above still requires authentication,
     * but there is no mechanism that can produce one - so every request carrying a
     * forwarded token is rejected with 401 and the service is effectively down
     * while every probe and metric stays green. {@link #meshSecurityFilterChain}
     * skips wiring the resource server in that case on purpose, so that local
     * development and tests boot without an identity provider; the cost of that
     * choice is that the broken state is invisible, and this is the control that
     * makes it visible.
     *
     * <p>Same guard as the gateway's {@code JwtValidationSafetyCheck}, for the same
     * reason: the condition shows up in no metric and no log line, so refusing to
     * start is the only reliable way to surface it.
     */
    @Bean
    @ConditionalOnMissingBean
    public MeshJwtValidationSafetyCheck meshJwtValidationSafetyCheck(
            Environment environment,
            ObjectProvider<JwtDecoder> jwtDecoderProvider) {
        return new MeshJwtValidationSafetyCheck(environment, jwtDecoderProvider);
    }

    /**
     * Refuses a production-like startup with no tenant allow-list.
     *
     * @see TenantAllowListSafetyCheck
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantAllowListSafetyCheck tenantAllowListSafetyCheck(
            Environment environment,
            MeshSecurityProperties properties) {
        return new TenantAllowListSafetyCheck(environment, properties);
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