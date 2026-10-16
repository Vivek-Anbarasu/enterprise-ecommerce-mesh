package com.enterprise.mesh.security;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The behaviour that matters here is which failures are loud and which are quiet.
 *
 * <p>A missing decoder in production has to stop the pod. In a test it has to be
 * tolerated, because the alternative is that every service's test suite needs an
 * identity provider to boot.
 */
class MeshJwtValidationSafetyCheckTest {

    /**
     * Web, not plain: the auto-configuration is conditional on a servlet
     * application, so an ApplicationContextRunner would skip it entirely and every
     * "should have failed" case here would pass for the wrong reason.
     */
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    // Jackson supplies the ObjectMapper the ProblemDetail handlers
                    // need. Without it the context fails on an unsatisfied
                    // dependency before reaching any assertion here, which would
                    // make every "expected to fail" test pass for the wrong reason.
                    org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                    // Supplies HttpSecurity, which meshSecurityFilterChain needs.
                    // ServletWebSecurityAutoConfiguration is the one that imports
                    // @EnableWebSecurity; plain SecurityAutoConfiguration is not.
                    org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                    // MVC, because Spring Security's CorsConfigurer resolves its
                    // CorsConfigurationSource from the MVC handler-mapping
                    // introspector. Without it the context fails inside
                    // meshSecurityFilterChain before reaching any assertion here.
                    org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration.class,
                    MeshSecurityAutoConfiguration.class));

    @Test
    void failsStartupInProductionWithNoDecoder() {
        runner.withPropertyValues("mesh.environment=production")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("No JwtDecoder is configured"));
    }

    @Test
    void failsStartupWhenProductionProfileIsActiveAndEnvironmentIsUnset() {
        // mesh.environment absent and 'production' active: treated as production,
        // so a typo in the environment name fails closed rather than open.
        runner.withPropertyValues("spring.profiles.active=production")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void toleratesMissingDecoderLocally() {
        runner.withPropertyValues("mesh.environment=local")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void toleratesMissingDecoderWhenMeshSecurityIsDisabled() {
        runner.withPropertyValues("mesh.enabled=false", "mesh.environment=local")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsWhenADecoderIsPresent() {
        // A no-op stub is enough: the check asks only whether a decoder exists,
        // never whether one is reachable, so a test must not need a real issuer.
        runner.withBean(JwtDecoder.class, () -> token -> { throw new IllegalStateException("stub"); })
                .withPropertyValues("mesh.environment=production")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void entraProfileWithoutTenantIdFailsFast() {
        // The dangerous case: an empty tenant id builds a syntactically valid
        // issuer URL pointing at the wrong directory, so the decoder exists, the
        // presence check passes, and every real token is then rejected as foreign.
        runner.withPropertyValues("spring.profiles.active=entra", "ENTRA_TENANT_ID=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("ENTRA_TENANT_ID is unset"));
    }

    @Test
    void entraProfileWithPlaceholderTenantIdFailsFast() {
        runner.withPropertyValues("spring.profiles.active=entra",
                        "ENTRA_TENANT_ID=REPLACE_WITH_ENTRA_DIRECTORY_TENANT_ID")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("still the placeholder value"));
    }

    @Test
    void entraProfileBuildsDecoderFromTenantId() {
        runner.withPropertyValues("spring.profiles.active=entra",
                        "ENTRA_TENANT_ID=11111111-2222-3333-4444-555555555555")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                });
    }

    @Test
    void explicitDecoderWinsOverTheEntraDefault() {
        // An operator pointing at a PEM from Key Vault must not also get the Entra
        // decoder, or the two disagree about what a valid token looks like and
        // which one validates the token comes down to bean ordering.
        JwtDecoder operatorSupplied = token -> {
            throw new IllegalStateException("operator decoder");
        };

        runner.withBean(JwtDecoder.class, () -> operatorSupplied)
                .withPropertyValues("spring.profiles.active=entra",
                        "ENTRA_TENANT_ID=11111111-2222-3333-4444-555555555555")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    assertThat(context.getBean(JwtDecoder.class)).isSameAs(operatorSupplied);
                });
    }

}
