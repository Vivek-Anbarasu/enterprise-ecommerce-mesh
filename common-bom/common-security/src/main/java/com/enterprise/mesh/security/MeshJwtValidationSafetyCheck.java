package com.enterprise.mesh.security;

import java.util.Arrays;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Fails startup if a production-like environment has no JWT decoder configured.
 *
 * <p>The gateway forwards a token it has already validated, so every service
 * behind it receives an {@code Authorization} header. That is a good reason to
 * keep validating it downstream rather than trusting the hop: a token that was
 * valid at the edge is not automatically valid now, a compromised pod can call
 * any other service directly without passing the gateway at all, and only the
 * issuer can say whether a given signature is still good.
 *
 * <p>The risk this class exists to remove is the opposite failure. Without a
 * decoder, {@code MeshSecurityAutoConfiguration} deliberately skips wiring the
 * resource server so that local development and the test suite boot with no
 * identity provider. In production that same branch produces a service that
 * starts cleanly, logs one WARN, passes every probe, and rejects all 401 - and it
 * does so fail-closed, so nothing is breached. But it presents as an
 * availability incident rather than a security one, which means nothing alarms
 * and the pods sit there serving 401s indefinitely.
 *
 * <p>So the state is refused at startup instead. That turns a silent outage into
 * a crash-loop that names the missing property.
 *
 * <p>This mirrors the gateway's own {@code JwtValidationSafetyCheck}. The gateway
 * needs its own because it does not depend on this library, and two services
 * arriving at the same failure from different directions should fail the same
 * way.
 */
@Slf4j
@RequiredArgsConstructor
public class MeshJwtValidationSafetyCheck implements InitializingBean {

    /** Environments where running without token validation is a deployment error. */
    private static final Set<String> PRODUCTION_ENVIRONMENTS =
            Set.of("prod", "production", "aks-prod", "live");

    private final Environment environment;

    private final ObjectProvider<JwtDecoder> jwtDecoderProvider;

    @Override
    public void afterPropertiesSet() {
        if (jwtDecoderProvider.getIfAvailable() != null) {
            log.info("Resource server configured: forwarded bearer tokens are validated against '{}'",
                    issuerOrDefault());
            return;
        }

        String meshEnv = environment.getProperty("mesh.environment", "");

        if (isProduction(meshEnv)) {
            throw new IllegalStateException(
                    "No JwtDecoder is configured in environment '" + meshEnv + "'. Without one this "
                            + "service rejects every request - including each token the gateway "
                            + "forwards - while reporting healthy, so it serves 401s indefinitely "
                            + "with nothing to alarm on it. Activate the 'entra' profile "
                            + "(SPRING_PROFILES_ACTIVE=production,entra) and set ENTRA_TENANT_ID, or "
                            + "configure spring.security.oauth2.resourceserver.jwt.* explicitly.");
        }

        log.warn("Starting WITHOUT JWT validation (environment '{}'). No decoder is configured, so "
                + "every request will be rejected. This is expected locally and must never happen in "
                + "a deployed environment.", meshEnv);
    }

    /** What the operator configured, for the log line that proves it took effect. */
    private String issuerOrDefault() {
        return environment.getProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "ENTRA tenant " + environment.getProperty("ENTRA_TENANT_ID", "(unset)"));
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
