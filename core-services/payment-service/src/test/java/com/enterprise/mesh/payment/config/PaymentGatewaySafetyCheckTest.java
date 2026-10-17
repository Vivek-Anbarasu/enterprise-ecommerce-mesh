package com.enterprise.mesh.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies the production guardrail on the sandbox gateway.
 *
 * <p>This is a safety-critical check: if it regressed, a production deployment
 * would authorise every payment without taking money, and nothing in the metrics
 * or the order flow would reveal it.
 */
class PaymentGatewaySafetyCheckTest {

    private static MockEnvironment env(String gateway, String meshEnv, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mesh.payment.gateway", gateway);
        environment.setProperty("mesh.environment", meshEnv);
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    @DisplayName("the stub gateway is rejected in production")
    void rejectsStubInProduction() {
        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(env("stub", "prod")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorises payments without taking money");
    }

    @Test
    @DisplayName("the stub gateway is rejected for an AKS production label")
    void rejectsStubInAksProd() {
        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(env("stub", "aks-prod")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the stub gateway is allowed in development")
    void allowsStubInDevelopment() {
        assertThatCode(() -> new PaymentGatewaySafetyCheck(env("stub", "local")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the stub gateway is allowed in staging, which is non-production")
    void allowsStubInStaging() {
        assertThatCode(() -> new PaymentGatewaySafetyCheck(env("stub", "staging")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a real gateway is allowed in production once the provider is configured")
    void allowsRealGatewayInProduction() {
        MockEnvironment environment = httpGatewayEnv("prod");
        environment.setProperty("mesh.payment.provider.base-url", "https://payments.example");
        environment.setProperty("mesh.payment.provider.api-key", "sk_live_real_key");

        assertThatCode(() -> new PaymentGatewaySafetyCheck(environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unknown gateway mode is rejected by name instead of failing as a missing bean")
    void rejectsUnknownMode() {
        // Previously this produced UnsatisfiedDependency: no bean of type PaymentGateway,
        // which points at the dependency wiring rather than the property that is wrong.
        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(env("strip", "prod")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown mesh.payment.gateway 'strip'")
                .hasMessageContaining("stub")
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("the gateway mode is matched case-insensitively")
    void modeIsCaseInsensitive() {
        MockEnvironment environment = httpGatewayEnv("prod");
        environment.setProperty("mesh.payment.provider.base-url", "https://payments.example");
        environment.setProperty("mesh.payment.provider.api-key", "sk_live_real_key");

        assertThatCode(() -> new PaymentGatewaySafetyCheck(environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the http gateway is rejected without a provider base url")
    void httpRequiresBaseUrl() {
        MockEnvironment environment = httpGatewayEnv("prod");
        environment.setProperty("mesh.payment.provider.api-key", "sk_live_real_key");

        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    @DisplayName("the http gateway is rejected without an api key")
    void httpRequiresApiKey() {
        MockEnvironment environment = httpGatewayEnv("prod");
        environment.setProperty("mesh.payment.provider.base-url", "https://payments.example");

        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    @DisplayName("a still-placeholder provider credential is rejected")
    void httpRejectsPlaceholderCredential() {
        MockEnvironment environment = httpGatewayEnv("prod");
        environment.setProperty("mesh.payment.provider.base-url", "https://payments.example");
        environment.setProperty("mesh.payment.provider.api-key",
                "REPLACE_WITH_PROVIDER_API_KEY");

        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("an unset gateway is treated as the stub and so is rejected in production")
    void defaultsToStubAndIsRejectedInProduction() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mesh.environment", "prod");

        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(environment).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty environment with an active production profile is treated as production")
    void productionProfileAloneIsEnough() {
        assertThatThrownBy(() -> new PaymentGatewaySafetyCheck(env("stub", "", "production")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment httpGatewayEnv(String meshEnv) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("mesh.payment.gateway", "http");
        environment.setProperty("mesh.environment", meshEnv);
        return environment;
    }
}
