package com.enterprise.mesh.payment.config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup if the sandbox payment gateway is configured in a production-like
 * environment.
 *
 * <p>The stub authorises any payment token that does not start with
 * {@code tok_decline}. Running it in production means every checkout succeeds
 * while no money is ever taken: the worst possible failure, because it looks
 * like success from the customer's side and from the order service's side. It
 * also means no order reaches the reconciliations that would reveal the problem.
 *
 * <p>Failing loudly at startup is the only reliable control, since the condition
 * is invisible in every metric and log line.
 *
 * <p><b>Known modes are validated too.</b> A mode with no implementation - a typo, or a
 * provider this service has no adapter for - used to fail as {@code UnsatisfiedDependency:
 * no bean of type PaymentGateway}, which points at the dependency wiring rather than at
 * the one property that is actually wrong. Naming the valid modes turns that into a
 * one-line fix. Each mode must also be backed by a bean conditional on it:
 * {@code stub} by {@code StubPaymentGateway}, {@code http} by {@code HttpPaymentGateway}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentGatewaySafetyCheck implements InitializingBean {

    /** Environments where a simulated gateway is a programming error. */
    private static final java.util.Set<String> PRODUCTION_ENVIRONMENTS =
            java.util.Set.of("prod", "production", "aks-prod", "live");

    /**
     * Modes with an implementation on the classpath. Kept in step with the
     * {@code @ConditionalOnProperty} values on the gateway beans; a mode listed here
     * without a matching bean will fail at startup, which is the intended direction.
     */
    private static final java.util.Set<String> KNOWN_MODES = java.util.Set.of("stub", "http");

    private final Environment environment;
    @Override
    public void afterPropertiesSet() {
        String gateway = environment.getProperty("mesh.payment.gateway", "stub");
        String meshEnv = environment.getProperty("mesh.environment", "");

        String mode = gateway.toLowerCase(java.util.Locale.ROOT);
        if (!KNOWN_MODES.contains(mode)) {
            throw new IllegalStateException(
                    "Unknown mesh.payment.gateway '" + gateway + "'. Known modes are " + KNOWN_MODES
                            + ". A mode with no implementation on the classpath cannot start this "
                            + "service; check the spelling, or add a PaymentGateway implementation "
                            + "conditional on mesh.payment.gateway=" + mode + ".");
        }

        if ("http".equals(mode)) {
            requireProviderConfiguration();
            log.info("Payment gateway mode '{}' configured for environment '{}'", gateway, meshEnv);
            return;
        }

        if (isProduction(meshEnv)) {
            throw new IllegalStateException(
                    "mesh.payment.gateway=stub in environment '" + meshEnv + "'. The sandbox "
                            + "gateway authorises payments without taking money and would silently "
                            + "produce free orders. Configure a real gateway before deploying to "
                            + "production.");
        }

        log.warn("Using the SANDBOX payment gateway (environment '{}'). Payments are simulated "
                + "and no money is taken. This must never be used in production.", meshEnv);
    }

    /**
     * A half-configured provider is worse than no provider at all: the service starts,
     * reports healthy, and then fails every payment at the point of sale. So the missing
     * values are caught here rather than on the first transaction.
     */
    private void requireProviderConfiguration() {
        requireSet("mesh.payment.provider.base-url", "PAYMENT_PROVIDER_BASE_URL");
        requireSet("mesh.payment.provider.api-key", "PAYMENT_PROVIDER_API_KEY");
    }

    private void requireSet(String property, String envVar) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "mesh.payment.gateway=http but " + property + " is unset. Every provider call "
                            + "would be rejected, which fails each payment at the point of sale "
                            + "rather than at deploy time. Supply it as " + envVar + ", from a "
                            + "Kubernetes secret rather than values.yaml.");
        }
        if (value.startsWith("REPLACE_")) {
            throw new IllegalStateException(
                    property + " is still the placeholder value '" + value + "'. Replace it with "
                            + "the real provider endpoint or credential.");
        }
    }

    /**
     * Treats an environment as production when it says so, or when it is
     * unrecognised and a production profile is active. An unknown value in a
     * production Spring profile is treated as production, so a typo fails closed.
     */
    private boolean isProduction(String meshEnv) {
        if (meshEnv != null && PRODUCTION_ENVIRONMENTS.contains(meshEnv.toLowerCase())) {
            return true;
        }
        if (meshEnv == null || meshEnv.isBlank()) {
            return java.util.Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(profile -> PRODUCTION_ENVIRONMENTS.contains(profile.toLowerCase()));
        }
        return false;
    }
}