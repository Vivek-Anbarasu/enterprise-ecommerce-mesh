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
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PaymentGatewaySafetyCheck implements InitializingBean {

    /** Environments where a simulated gateway is a programming error. */
    private static final java.util.Set<String> PRODUCTION_ENVIRONMENTS =
            java.util.Set.of("prod", "production", "aks-prod", "live");

    private final Environment environment;
    @Override
    public void afterPropertiesSet() {
        String gateway = environment.getProperty("mesh.payment.gateway", "stub");
        String meshEnv = environment.getProperty("mesh.environment", "");

        if (!"stub".equalsIgnoreCase(gateway)) {
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