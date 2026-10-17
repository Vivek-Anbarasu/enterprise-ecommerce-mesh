package com.enterprise.mesh.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.enterprise.mesh.ai.config.MeshAiProperties;

/**
 * Asserts that {@code mesh.ai.*} is actually bound, rather than merely declared.
 *
 * <p>This exists because both keys were inert until {@code MeshAiProperties} was
 * added. {@code application.yml} declared {@code provider} and
 * {@code max-recommendations}, two Helm values files set {@code AI_PROVIDER}, and
 * {@code RecommendationService} carried its own {@code MAX_RECOMMENDATIONS = 20}
 * constant - so the key existed, the env var existed, and neither reached any code.
 * An unset-then-ignored key cannot be detected by reading the running service, which
 * is exactly why it survived.
 *
 * <p>The override here is deliberately {@code 3}, not the YAML default of {@code 20}:
 * a test asserting the default proves nothing about binding, because the field
 * initialiser would produce the same value whether or not the YAML were read.
 */
@SpringBootTest(properties = {
        "mesh.tenant.allow-all=true",
        "mesh.ai.max-recommendations=3"
})
class MeshAiPropertiesTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeshAiProperties properties;

    @Test
    @DisplayName("mesh.ai is registered as a bean, which it was not while the keys were inert")
    void propertiesAreABean() {
        assertThat(context.getBeanNamesForType(MeshAiProperties.class))
                .as("No bean means nothing reads mesh.ai.*, so the keys bind to nothing and "
                        + "every value an operator sets is discarded silently.")
                .hasSize(1);
    }

    @Test
    @DisplayName("mesh.ai.max-recommendations binds and overrides the YAML default")
    void maxRecommendationsBinds() {
        assertThat(properties.getMaxRecommendations())
                .as("Sticking at 20 here means either the YAML was never read or the field "
                        + "initialiser is winning - the two ways this regresses.")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("mesh.ai.provider binds to the YAML default of 'none'")
    void providerBinds() {
        assertThat(properties.getProvider()).isEqualTo("none");
    }

    @Test
    @DisplayName("the defaults are valid, so a deployment with no configuration starts")
    void defaultsValidate() {
        assertThat(violationsOf(new MeshAiProperties()))
                .as("An unset key must not fail startup - the whole point of a default is that "
                        + "the service runs before anyone configures it.")
                .isEmpty();
    }

    @Test
    @DisplayName("a non-positive recommendation limit is rejected, because Stream.limit throws on it")
    void nonPositiveLimitIsRejected() {
        // The failure this prevents: AI_MAX_RECOMMENDATIONS=-1 reaching Stream.limit()
        // on the storefront home page, which is a 500 on the most-trafficked endpoint
        // in the service rather than a startup error.
        for (int invalid : new int[]{0, -1}) {
            MeshAiProperties props = new MeshAiProperties();
            props.setMaxRecommendations(invalid);

            assertThat(violationsOf(props))
                    .as("maxRecommendations=%d must be rejected", invalid)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("a blank provider is rejected")
    void blankProviderIsRejected() {
        // A blank provider would also defeat the startup warning, which compares
        // against "none" - so the check must reject it rather than merely ignore it.
        MeshAiProperties props = new MeshAiProperties();
        props.setProvider("  ");

        assertThat(violationsOf(props)).isNotEmpty();
    }

    @Test
    @DisplayName("an unimplemented provider is accepted, because blocking on it would help nobody")
    void unimplementedProviderIsAccepted() {
        // Deliberately not a validation failure. There is no provider wired, so the
        // value changes no behaviour either way; refusing to start would take a
        // deployment down over a key that was always going to be ignored. The
        // provider is logged at startup instead - see MeshAiProperties#reportEffectiveConfiguration.
        MeshAiProperties props = new MeshAiProperties();
        props.setProvider("openai");

        assertThat(violationsOf(props)).isEmpty();
    }

    private Set<?> violationsOf(MeshAiProperties props) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(props);
        }
    }
}