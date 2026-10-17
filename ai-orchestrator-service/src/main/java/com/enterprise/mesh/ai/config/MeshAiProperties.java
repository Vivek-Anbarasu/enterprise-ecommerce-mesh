package com.enterprise.mesh.ai.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the recommendation engine, bound from {@code mesh.ai.*}.
 *
 * <p>This class exists because both keys under {@code mesh.ai} were inert.
 * {@code provider} and {@code max-recommendations} were declared in
 * {@code application.yml}, were overridden from the Helm values, and looked
 * configurable - but no {@code @ConfigurationProperties} bound {@code mesh.ai} and
 * no {@code @Value} referenced either key. {@code RecommendationService} carried its
 * own {@code MAX_RECOMMENDATIONS = 20} constant instead, so
 * {@code AI_MAX_RECOMMENDATIONS} could be set to anything at all without changing a
 * single recommendation. Nothing in the configuration file hinted at that.
 *
 * <p><b>What this class deliberately does not do.</b> It is not the missing provider
 * seam, and it should not be mistaken for one.
 * {@link com.enterprise.mesh.ai.service.RecommendationService#rank} still has no caller,
 * and no model provider is wired behind {@link #provider} - the ranking is the
 * heuristic in {@code RecommendationService}, full stop. What binding buys is
 * observability: both keys are now read, so an operator who sets one gets either its
 * effect or an explicit warning at startup, rather than silence. The warning is the
 * point. A silently ignored {@code AI_PROVIDER=openai} is worse than a missing key,
 * because it reads as "an LLM is ranking these" when nothing of the kind is happening.
 *
 * <p>{@link #maxRecommendations} is validated rather than trusted, because it reaches
 * {@code Stream.limit()}, and a negative limit throws on the storefront's home page
 * path rather than at deploy time.
 */
@Validated
@ConfigurationProperties(prefix = "mesh.ai")
@Getter
@Setter
@Slf4j
public class MeshAiProperties {

    /** The only value that is honoured; see the class javadoc. */
    public static final String PROVIDER_NONE = "none";

    /** Matches the {@code AI_MAX_RECOMMENDATIONS} default in application.yml. */
    private static final int DEFAULT_MAX_RECOMMENDATIONS = 20;

    /**
     * Which ranking implementation to use.
     *
     * <p>Only {@link #PROVIDER_NONE} is implemented. Any other value is accepted,
     * bound and logged, but has no effect on ranking - so it warns at startup rather
     * than failing, to keep a deployment from being blocked on a key that was always
     * going to be ignored anyway.
     */
    @NotBlank
    private String provider = PROVIDER_NONE;

    /**
     * Ceiling on recommendations, applied both to the preference list a shopper's
     * affinities produce and to the ranked output.
     *
     * <p>Must be positive: zero would return an empty list to every shopper, and a
     * negative value reaches {@code Stream.limit()} and throws.
     */
    @Min(1)
    private int maxRecommendations = DEFAULT_MAX_RECOMMENDATIONS;

    /**
     * Reports the effective configuration once, at startup.
     *
     * <p>Exists because {@code mesh.ai.provider} was previously set from two Helm
     * values files and read by nothing, so a value that looks active is
     * indistinguishable from one that is. Logging it makes the difference visible
     * without having to read the source to work out what a deployment is doing.
     */
    @PostConstruct
    void reportEffectiveConfiguration() {
        log.info("Recommendation ranking: heuristic, maxRecommendations={}", maxRecommendations);

        if (!PROVIDER_NONE.equalsIgnoreCase(provider)) {
            log.warn("mesh.ai.provider='{}' is not honoured - no model provider is wired in "
                    + "this service, so ranking stays on the heuristic in "
                    + "RecommendationService regardless of this value. Set it to '{}' to "
                    + "silence this.", provider, PROVIDER_NONE);
        }
    }
}