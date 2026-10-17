package com.enterprise.mesh.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Covers the guard that stops a production deployment shipping with no tenant
 * allow-list.
 *
 * <p>Every service in the mesh shipped with {@code mesh.tenant.allowed} unset, which
 * means "allow all tenants" — so the {@code 403 Tenant Not Permitted} branch was
 * unreachable everywhere. The condition is invisible at runtime, which is exactly why
 * the control is a startup refusal.
 */
class TenantAllowListSafetyCheckTest {

    private static MockEnvironment env(String meshEnv, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mesh.environment", meshEnv);
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static TenantAllowListSafetyCheck check(MockEnvironment environment,
                                                     java.util.List<String> allowed,
                                                     boolean allowAll) {
        MeshSecurityProperties properties = new MeshSecurityProperties();
        properties.getTenant().setAllowed(allowed);
        properties.getTenant().setAllowAll(allowAll);
        return new TenantAllowListSafetyCheck(environment, properties);
    }

    @Test
    @DisplayName("an empty allow-list is refused in production")
    void emptyAllowListRejectedInProduction() {
        assertThatThrownBy(() -> check(env("prod"), java.util.List.of(), false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mesh.tenant.allowed is empty");
    }

    @Test
    @DisplayName("an empty allow-list is refused for an AKS production label")
    void emptyAllowListRejectedInAksProd() {
        assertThatThrownBy(() -> check(env("aks-prod"), java.util.List.of(), false).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty allow-list is refused for prod, production and live alike")
    void emptyAllowListRejectedForEveryProductionLabel() {
        for (String envName : java.util.List.of("prod", "production", "aks-prod", "live")) {
            assertThatThrownBy(() -> check(env(envName), java.util.List.of(), false)
                    .afterPropertiesSet())
                    .as("mesh.environment=%s", envName)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("an empty allow-list with no environment but a production profile is refused")
    void productionProfileAloneIsEnough() {
        assertThatThrownBy(() -> check(env("", "production"), java.util.List.of(), false)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a populated allow-list starts anywhere, production included")
    void populatedAllowListAlwaysPasses() {
        assertThatCode(() -> check(env("aks-prod"), java.util.List.of("acme", "globex"), false)
                .afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allow-all=true starts in production, having asserted the decision")
    void allowAllOptsOut() {
        assertThatCode(() -> check(env("aks-prod"), java.util.List.of(), true).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty allow-list is only a warning outside production")
    void emptyAllowListWarnsLocally() {
        assertThatCode(() -> check(env("local"), java.util.List.of(), false).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> check(env("staging"), java.util.List.of(), false).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the refusal names both remedies, so the message is actionable")
    void messageNamesBothRemedies() {
        assertThatThrownBy(() -> check(env("prod"), java.util.List.of(), false).afterPropertiesSet())
                .hasMessageContaining("global.mesh.tenantAllowed")
                .hasMessageContaining("mesh.tenant.allow-all=true");
    }
}