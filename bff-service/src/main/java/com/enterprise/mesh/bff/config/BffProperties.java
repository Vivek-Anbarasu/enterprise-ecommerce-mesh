package com.enterprise.mesh.bff.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BFF-specific configuration, bound from {@code mesh.bff.*}.
 *
 * <p>Deliberately does not reuse {@code MeshSecurityProperties}: this service is
 * stateful and cookie-authenticated, whereas that one describes the stateless
 * bearer-token posture every other mesh service runs with.
 */
@ConfigurationProperties(prefix = "mesh.bff")
public class BffProperties {

    /**
     * Base URI of the {@code api-gateway}. The BFF has no direct route to any
     * business service: everything goes through the gateway so that rate limiting,
     * tenant re-derivation and per-route circuit breaking stay in exactly one place.
     */
    private String gatewayUri = "http://api-gateway:8080";

    /**
     * Key under {@code spring.security.oauth2.client.registration} whose token
     * bundle is kept in the session.
     *
     * <p>Named rather than hardcoded so a second registration (say a BFF for partner
     * users on a different directory) can be added without touching code.
     */
    private String registrationId = "entra";

    /** TCP connect budget for a single downstream call. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Response budget for a single downstream call. Deliberately shorter than the
     * gateway's own 5s time limiter: an aggregation that cannot finish inside the
     * browser's patience should degrade to a partial payload, not hang.
     */
    private Duration readTimeout = Duration.ofSeconds(8);

    /**
     * Renew the access token when it expires within this window.
     *
     * <p>Non-zero on purpose. A zero skew refreshes only tokens that have already
     * expired, so a token that dies in flight turns every request in that window
     * into a 401 from the gateway. Refreshing a little early trades one extra
     * token endpoint call for never losing a request to a clock boundary.
     */
    private Duration refreshSkew = Duration.ofSeconds(90);

    /**
     * Budget for one whole aggregation, across every downstream call it fans out
     * to. Past this the partial result is returned and the failures are reported in
     * the payload rather than as an error, so one slow service does not blank the
     * whole page.
     */
    private Duration aggregationTimeout = Duration.ofSeconds(5);

    /**
     * Origins permitted to call this service with credentials.
     *
     * <p>Not {@code *}, unlike the gateway's allow-list. Credentialed CORS forbids a
     * wildcard origin outright, and {@code allowedOriginPatterns("*")} alongside
     * {@code allowCredentials(true)} would defeat SameSite on the session cookie -
     * any site on the internet could then make the shopper's browser ride its
     * session. The deployed allow-list is short and enumerated on purpose.
     */
    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://localhost:5173");

    /** Header the SPA echoes the CSRF token back in. */
    private String csrfHeader = "X-XSRF-TOKEN";

    /** Name of the cookie the CSRF token is published in. */
    private String csrfCookieName = "XSRF-TOKEN";

    /**
     * Enables the refresh-token grant.
     *
     * <p>Off by default so that a deployment without a refresh token (an Entra
     * public-client registration configured without one, or a misconfigured scope
     * that never returns one) fails loudly at sign-in rather than silently
     * producing a session that dies an hour later with an opaque error.
     */
    private boolean refreshEnabled = true;

    public String getGatewayUri() {
        return gatewayUri;
    }

    public void setGatewayUri(String gatewayUri) {
        this.gatewayUri = gatewayUri;
    }

    public String getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(String registrationId) {
        this.registrationId = registrationId;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getRefreshSkew() {
        return refreshSkew;
    }

    public void setRefreshSkew(Duration refreshSkew) {
        this.refreshSkew = refreshSkew;
    }

    public Duration getAggregationTimeout() {
        return aggregationTimeout;
    }

    public void setAggregationTimeout(Duration aggregationTimeout) {
        this.aggregationTimeout = aggregationTimeout;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getCsrfHeader() {
        return csrfHeader;
    }

    public void setCsrfHeader(String csrfHeader) {
        this.csrfHeader = csrfHeader;
    }

    public String getCsrfCookieName() {
        return csrfCookieName;
    }

    public void setCsrfCookieName(String csrfCookieName) {
        this.csrfCookieName = csrfCookieName;
    }

    public boolean isRefreshEnabled() {
        return refreshEnabled;
    }

    public void setRefreshEnabled(boolean refreshEnabled) {
        this.refreshEnabled = refreshEnabled;
    }
}