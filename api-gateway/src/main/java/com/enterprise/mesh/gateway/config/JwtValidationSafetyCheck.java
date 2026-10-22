package com.enterprise.mesh.gateway.config;

import java.util.Arrays;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Fails startup if a production-like environment has no JWT decoder configured.
 *
 * <p>Without a decoder the gateway does not fail open - it fails closed. Every
 * authenticated route returns 401 and nothing reaches a backend, which is the
 * safe direction and also why this went unnoticed for so long: the process
 * starts cleanly, logs a single WARN, and looks healthy to a readiness probe.
 * The symptom is not a breach, it is a gateway that serves nothing but the
 * anonymous catalogue, with the metrics still green.
 *
 * <p>That is a deployment failure that presents as an availability incident
 * rather than a security one, so nothing alarms. A readiness probe cannot see it
 * because the readiness group only covers Redis and the lifecycle state. Failing
 * the pod at startup instead turns it into a crash-loop that says exactly what is
 * wrong, before traffic reaches it at all.
 *
 * <p>This is the same guard as {@code PaymentGatewaySafetyCheck}, for the same
 * reason: the condition is invisible in every metric and log line, so the only
 * reliable control is refusing to start.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtValidationSafetyCheck implements InitializingBean {

    /** Environments where running without token validation is a deployment error. */
    private static final Set<String> PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "aks-prod", "live");

    private final Environment environment;

    private final ObjectProvider<JwtDecoder> jwtDecoderProvider;

    @Override
    public void afterPropertiesSet() {
        if (isEntraProfileActive()) {
            requireTenantId();
            requireAudience();
        }

        if (jwtDecoderProvider.getIfAvailable() != null) {
            log.info("Gateway resource server configured: bearer tokens are validated at the edge "
                    + "against '{}'", environment.getProperty(
                            "spring.security.oauth2.resourceserver.jwt.issuer-uri", "(default)"));
            return;
        }

        String meshEnv = environment.getProperty("mesh.environment", "");

        if (isProduction(meshEnv)) {
            throw new IllegalStateException(
                    "No JwtDecoder is configured in environment '" + meshEnv + "'. Without one the "
                            + "gateway accepts no bearer tokens, so every authenticated route returns "
                            + "401 and the service is unusable while still reporting healthy. This "
                            + "reads as an availability incident rather than a security one, which "
                            + "is why it fails the pod instead of warning. Activate the 'entra' "
                            + "profile (SPRING_PROFILES_ACTIVE=production,entra) and set "
                            + "ENTRA_TENANT_ID.");
        }

        log.warn("Gateway starting WITHOUT JWT validation (environment '{}'). Only anonymous "
                + "routes will be served. This is expected locally and must never happen in a "
                + "deployed environment.", meshEnv);
    }

    /**
     * Rejects a missing or still-placeholder tenant id whenever the entra profile is
     * active.
     *
     * <p>Worth its own check because the failure is invisible. The issuer URL is
     * built by interpolating this value, so a blank one yields
     * {@code https://login.microsoftonline.com//v2.0} - a syntactically valid URL
     * pointing at the wrong directory. A decoder is still created, so the
     * no-decoder guard below is satisfied and the pod starts happily, then rejects
     * every real token as belonging to another tenant. Fail closed, but silently,
     * which is the same category of problem this whole class exists to eliminate.
     */
    private void requireTenantId() {
        String tenantId = environment.getProperty("ENTRA_TENANT_ID");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    "The 'entra' profile is active but ENTRA_TENANT_ID is unset. The issuer URL is "
                            + "built from it, so an empty value produces a valid-looking URL "
                            + "pointing at the wrong directory: the gateway would start, pass its "
                            + "health checks, and reject every real token as foreign. Set the "
                            + "directory (tenant) id, e.g. with "
                            + "--set services.api-gateway.extraEnv.ENTRA_TENANT_ID=<directory-id>.");
        }
        if (tenantId.startsWith("REPLACE_")) {
            throw new IllegalStateException(
                    "ENTRA_TENANT_ID is still the placeholder value '" + tenantId + "'. Replace it "
                            + "with the directory (tenant) id from the Entra app registration; the "
                            + "same value must be set for the bff-service, because the gateway pins "
                            + "its issuer to exactly that directory.");
        }
    }

    private boolean isEntraProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "entra".equalsIgnoreCase(profile));
    }

    /**
     * Refuses an {@code entra} startup with no audience configured.
     *
     * <p>Signature and issuer are not enough. Entra signs every tenant's tokens
     * with a shared key set, and issuer identifies the directory rather than the
     * intended recipient - so without an {@code aud} check the gateway happily
     * accepts a token minted for an unrelated application in the same directory.
     */
    private void requireAudience() {
        String audience = environment.getProperty("ENTRA_AUDIENCE");
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "The 'entra' profile is active but ENTRA_AUDIENCE is unset. The gateway would "
                            + "validate signature and issuer but accept a token minted for any other "
                            + "application in this directory. Set the API's Application ID URI "
                            + "(or its client-id GUID) as ENTRA_AUDIENCE, e.g. with "
                            + "--set global.entraAudience=<api-application-id>.");
        }
    }

    /**
     * Treats an environment as production when it says so, or when it is
     * unrecognised and a production profile is active. An unknown value under a
     * production profile is treated as production, so a typo fails closed.
     */
    private boolean isProduction(String meshEnv) {
        if (meshEnv != null && PRODUCTION_ENVIRONMENTS.contains(meshEnv.toLowerCase())) {
            return true;
        }
        if (meshEnv == null || meshEnv.isBlank()) {
            return Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(profile -> PRODUCTION_ENVIRONMENTS.contains(profile.toLowerCase()));
        }
        return false;
    }
}