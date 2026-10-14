package com.enterprise.mesh.bff.config;

import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

import com.enterprise.mesh.bff.security.CsrfCookieWritingFilter;
import com.enterprise.mesh.bff.security.SpaAccessDeniedHandler;
import com.enterprise.mesh.bff.security.SpaAuthenticationEntryPoint;
import com.enterprise.mesh.bff.security.SpaCsrfTokenRequestAttributeHandler;
import com.enterprise.mesh.bff.session.SessionTokenService;
import com.enterprise.mesh.security.tenant.TenantContextFilter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Browser-facing security for the BFF.
 *
 * <p>Structurally the inverse of every other service in the mesh. Those are
 * stateless bearer endpoints: CSRF off, session policy STATELESS, a
 * {@code WWW-Authenticate: Bearer} challenge. This one authenticates the browser by
 * cookie, so all three are inverted - and each inversion is a genuine requirement,
 * not a preference:
 *
 * <ul>
 *   <li>Sessions are {@code IF_REQUIRED}, not stateless: the whole point is that a
 *       server-side session outlives the access token.</li>
 *   <li>CSRF stays on. A cookie is attached by the browser without any cooperation
 *       from the SPA, so an unauthenticated cross-site form could otherwise drive
 *       an authenticated checkout. The gateway can disable CSRF precisely because
 *       its bearer token is never attached automatically; this service cannot.</li>
 *   <li>Session fixation protection switches the session id on privilege change,
 *       which is the one moment an attacker could otherwise have pre-chosen it.</li>
 * </ul>
 *
 * <p>The authorization rules end in {@code denyAll} rather than
 * {@code authenticated}. This service has a small, deliberate API surface; anything
 * that grows a mapping by accident fails closed instead of quietly becoming part of
 * the public contract.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class BffSecurityConfig {

    /**
     * Where the SPA is sent to sign in. Lives under {@code /bff} so that the
     * north-south edge can route the whole BFF surface with one path prefix while
     * every other client keeps calling the gateway directly.
     */
    public static final String AUTHORIZATION_URI = "/bff/oauth2/authorization";

    /** Wildcard tail: the identity provider appends the registration id. */
    public static final String CALLBACK_URI = "/bff/oauth2/callback/*";

    public static final String LOGOUT_URI = "/bff/logout";

    @Bean
    public SecurityFilterChain bffSecurityFilterChain(
            HttpSecurity http,
            BffProperties properties,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            SpaAuthenticationEntryPoint authenticationEntryPoint,
            SpaAccessDeniedHandler accessDeniedHandler) throws Exception {

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository(properties))
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestAttributeHandler())
                        // The callback is a GET redirect from the identity provider.
                        // It is reachable before the session is authenticated, so it
                        // carries no CSRF token to present.
                        .ignoringRequestMatchers(CALLBACK_URI))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; base-uri 'self'"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info",
                                         "/actuator/prometheus").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                         "/swagger-ui.html").permitAll()
                        // Spring's generated login page; the SPA normally goes
                        // straight to AUTHORIZATION_URI instead of rendering it.
                        .requestMatchers("/login").permitAll()
                        .requestMatchers("/bff/oauth2/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Anonymous browsing: the same surface the gateway exposes
                        // without a token.
                        .requestMatchers("/bff/api/public/**").permitAll()
                        .requestMatchers("/bff/api/**").authenticated()
                        .anyRequest().denyAll())
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URI)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        // Must match server.servlet.session.cookie-name in
                        // application.yml, or the browser keeps presenting an id
                        // whose server-side session no longer exists.
                        .deleteCookies("SESSION")
                        // 204 rather than a redirect: a logout the SPA did not
                        // initiate has no page to land on.
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value())));

        // Wired only when a client registration exists, mirroring the gateway's
        // conditional resource server. An environment with no Entra credentials
        // configured must still boot: it is how the local dev loop and the unit
        // tests work, and failing startup would make "forgot to set a secret" a
        // crash loop instead of a logged warning.
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .authorizationEndpoint(endpoint -> endpoint.baseUri(AUTHORIZATION_URI))
                    .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_URI))
                    // false, not true: a saved request (the page the shopper
                    // actually wanted) outranks this default.
                    .defaultSuccessUrl("/bff/api/session", false));
            log.info("BFF login enabled: authorization endpoint at {}", AUTHORIZATION_URI);
        } else {
            log.warn("BFF starting WITHOUT OAuth2 login. No client registration is configured "
                    + "(set spring.security.oauth2.client.registration.entra.*). Every /bff/api "
                    + "endpoint will reject requests.");
        }

        http.addFilterAfter(new CsrfCookieWritingFilter(), CsrfFilter.class);

        return http.build();
    }

    /**
     * Keeps {@code common-security}'s {@code TenantContextFilter} out of the
     * servlet pipeline.
     *
     * <p>Subtle, and worth stating plainly. That filter is an ordinary
     * {@code Filter} <em>bean</em>, and Spring Boot registers every such bean with
     * the embedded container automatically - whether or not it is in the Spring
     * Security chain. Adding a security filter chain of one's own does not switch
     * it off.
     *
     * <p>It does not belong on this service. Its job is to resolve the tenant from
     * a JWT claim and publish it to a {@code ThreadLocal} that business services
     * read; the BFF reads no {@code TenantContext} at all, since tenancy is
     * resolved downstream from the validated access token. Worse, with nothing to
     * read a claim from it falls through to the {@code X-Tenant-ID} <em>request
     * header</em> - which on a browser edge is simply whatever the caller sent -
     * and then echoes that value back as a response header and into the log MDC.
     *
     * <p>Left enabled with the default {@code required: true}, it also rejects
     * every anonymous request with 400 "Tenant Required", which takes the
     * browsable storefront down entirely.
     */
    @Bean
    public FilterRegistrationBean<TenantContextFilter> disableTenantContextFilter(
            TenantContextFilter tenantContextFilter) {
        FilterRegistrationBean<TenantContextFilter> registration =
                new FilterRegistrationBean<>(tenantContextFilter);
        registration.setEnabled(false);
        log.info("TenantContextFilter disabled: the BFF resolves no ThreadLocal tenant "
                + "and must not trust a caller-supplied X-Tenant-ID header.");
        return registration;
    }

    /**
     * Token bundle storage for the session.
     *
     * <p>{@link HttpSessionOAuth2AuthorizedClientRepository} reads and writes the
     * bundle as an ordinary session attribute, which Spring Session then persists
     * to Redis. That is the whole mechanism: no bespoke key format, no separate
     * store to keep in step with the session TTL.
     *
     * <p>Declared with its concrete type so it is clearly the same instance the
     * login flow writes to and {@code SessionTokenService} reads from - the two
     * must not end up with different repositories.
     */
    @Bean
    public HttpSessionOAuth2AuthorizedClientRepository bffAuthorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    /**
     * Performs the actual refresh-token grant.
     *
     * <p>A bean rather than a private field so a test can substitute a provider
     * that rotates a token instead of reaching for a live identity provider.
     */
    @Bean
    public OAuth2AuthorizedClientProvider bffRefreshTokenProvider(BffProperties properties, Clock clock) {
        return SessionTokenService.refreshTokenProvider(properties, clock);
    }

    /**
     * CSRF token published in a JavaScript-readable cookie.
     *
     * <p>{@code httpOnly} is false here by design and the CSRF token is not a
     * secret: its job is to prove the request came from a page that could already
     * read the cookie, which is meaningless if JavaScript cannot read it. The
     * session cookie stays {@code HttpOnly}; only this one is exposed.
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(BffProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(properties.getCsrfCookieName());
        repository.setHeaderName(properties.getCsrfHeader());
        repository.setCookiePath("/");
        return repository;
    }

    /**
     * Overrides {@code common-security}'s CORS source by reusing its bean name, so
     * the auto-configuration's {@code @ConditionalOnMissingBean} backs off.
     *
     * <p>The difference that matters is wildcard handling. The shared source pairs
     * {@code allowedOriginPatterns("*")} with {@code allowCredentials(true)}, which
     * the shared code documents as being narrowed by the ingress. That reasoning
     * does not transfer here: the BFF's origin is the storefront itself, it is known
     * and few, and its cookie is SameSite-protected but still worth not offering to
     * arbitrary origins. Hence exact origins and no patterns.
     */
    @Bean(name = "meshCorsConfigurationSource")
    public CorsConfigurationSource bffCorsConfigurationSource(BffProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "Accept", "X-Requested-With", "X-Request-ID",
                properties.getCsrfHeader()));
        configuration.setExposedHeaders(List.of("X-Request-ID", "Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        log.info("BFF accepts credentialed requests from origins {}", properties.getAllowedOrigins());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Exposed so other components do not have to re-derive the login URL. The
     * SPA needs it in a 401 body, and hardcoding the path in JavaScript is how the
     * two drift apart.
     */
    @Bean
    public SpaAuthenticationEntryPoint spaAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return new SpaAuthenticationEntryPoint(objectMapper, AUTHORIZATION_URI);
    }

    @Bean
    public SpaAccessDeniedHandler spaAccessDeniedHandler(ObjectMapper objectMapper,
                                                        BffProperties properties) {
        return new SpaAccessDeniedHandler(objectMapper, properties.getCsrfCookieName(),
                properties.getCsrfHeader());
    }
}