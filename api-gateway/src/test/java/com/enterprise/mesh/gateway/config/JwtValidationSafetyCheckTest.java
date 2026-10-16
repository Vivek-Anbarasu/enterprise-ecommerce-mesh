package com.enterprise.mesh.gateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Verifies the gateway refuses to start in production without token validation.
 *
 * <p>Safety-critical, and in an unusual way: the condition being guarded is not a
 * breach. Without a decoder the gateway rejects every token, so nothing is ever
 * served to an unauthenticated caller. What it costs is a gateway that accepts no
 * traffic at all while still reporting healthy to its probes - an outage that
 * looks like nothing is wrong. That is precisely the failure a readiness probe
 * cannot detect and a human notices far too late, so the only reliable control is
 * refusing to start.
 */
class JwtValidationSafetyCheckTest {

    private static MockEnvironment env(String meshEnv, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mesh.environment", meshEnv);
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static MockEnvironment entraEnv(String tenantId, String... profiles) {
        String[] active = new String[profiles.length + 1];
        active[0] = "entra";
        System.arraycopy(profiles, 0, active, 1, profiles.length);
        MockEnvironment environment = env("prod", active);
        if (tenantId != null) {
            environment.setProperty("ENTRA_TENANT_ID", tenantId);
        }
        return environment;
    }

    /**
     * No decoder bean, which is the state this check exists to catch.
     *
     * <p>The specific exception matters: {@code getIfAvailable()} swallows exactly
     * {@code NoSuchBeanDefinitionException} and lets anything else through, so a
     * stub throwing a generic error would test nothing.
     */
    private static ObjectProvider<JwtDecoder> noDecoder() {
        return new ObjectProvider<>() {
            @Override
            public JwtDecoder getObject() {
                throw new NoSuchBeanDefinitionException(JwtDecoder.class);
            }

            @Override
            public JwtDecoder getObject(Object... args) {
                throw new NoSuchBeanDefinitionException(JwtDecoder.class);
            }
        };
    }

    /** A decoder bean is present, i.e. the entra profile is active and resolved. */
    private static ObjectProvider<JwtDecoder> decoderPresent() {
        JwtDecoder decoder = token -> {
            throw new UnsupportedOperationException();
        };
        return new ObjectProvider<>() {
            @Override
            public JwtDecoder getObject() {
                return decoder;
            }

            @Override
            public JwtDecoder getObject(Object... args) {
                return decoder;
            }
        };
    }

    private static JwtValidationSafetyCheck check(MockEnvironment env, ObjectProvider<JwtDecoder> decoders) {
        return new JwtValidationSafetyCheck(env, decoders);
    }

    @Test
    @DisplayName("production without a decoder fails startup")
    void failsInProduction() {
        assertThatThrownBy(() -> check(env("prod"), noDecoder()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENTRA_TENANT_ID");
    }

    @Test
    @DisplayName("every production-like environment label fails startup")
    void failsForAllProductionLabels() {
        for (String env : new String[]{"prod", "production", "aks-prod", "live"}) {
            assertThatThrownBy(() -> check(env(env), noDecoder()).afterPropertiesSet())
                    .as("environment '%s'", env)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("an unknown environment under a production profile still fails")
    void unknownEnvironmentWithProductionProfileFails() {
        // A typo in mesh.environment must not be the thing that lets a keyless
        // gateway reach production, so the unrecognised value fails closed.
        assertThatThrownBy(() -> check(env("", "production"), noDecoder()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("local development starts without a decoder")
    void allowsLocalWithoutDecoder() {
        assertThatCode(() -> check(env("local"), noDecoder()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("staging starts without a decoder, which is not production")
    void allowsStagingWithoutDecoder() {
        assertThatCode(() -> check(env("staging"), noDecoder()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a configured decoder starts in production")
    void allowsProductionWithDecoder() {
        // The whole point: once the entra profile resolves a decoder, production is
        // fine and the guard must not get in the way.
        assertThatCode(() -> check(env("prod"), decoderPresent()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the entra profile without a tenant id fails startup")
    void entraWithoutTenantIdFails() {
        // The trap: an unset ENTRA_TENANT_ID still produces a syntactically valid
        // issuer URL pointing at the wrong directory, so a decoder is created, the
        // no-decoder guard is satisfied, and the pod starts and passes its probes
        // while rejecting every real token as foreign.
        assertThatThrownBy(() -> check(entraEnv(null), decoderPresent()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENTRA_TENANT_ID");
    }

    @Test
    @DisplayName("the entra profile with a still-placeholder tenant id fails startup")
    void entraWithPlaceholderTenantIdFails() {
        assertThatThrownBy(() -> check(
                entraEnv("REPLACE_WITH_ENTRA_DIRECTORY_TENANT_ID"), decoderPresent()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("the entra profile with a real tenant id starts")
    void entraWithTenantIdPasses() {
        assertThatCode(() -> check(
                entraEnv("11111111-1111-1111-1111-111111111111"), decoderPresent()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the tenant id is not required without the entra profile")
    void tenantIdNotRequiredWithoutEntraProfile() {
        // Local dev and the test suite boot with no identity provider at all, and
        // this guard must not be what stops them.
        assertThatCode(() -> check(env("local"), noDecoder()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}