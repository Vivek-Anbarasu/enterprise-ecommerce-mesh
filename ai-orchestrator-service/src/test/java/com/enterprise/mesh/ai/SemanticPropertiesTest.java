package com.enterprise.mesh.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import com.enterprise.mesh.ai.semantic.SemanticProperties;

/**
 * Asserts {@code mesh.ai.semantic.*} is bound rather than merely declared.
 *
 * <p>This exists because it was broken and nothing caught it. The block was mis-indented to
 * {@code mesh.semantic.*} - a sibling of {@code mesh.ai}, not a child - so every value silently
 * fell back to its Java field default while the YAML read as though it were configuring
 * something. {@link SemanticProperties} still validated, because the defaults happened to be
 * valid, so the context started and every test passed.
 *
 * <p>The same class of bug as the {@code mesh.ai.provider} key that was inert until
 * {@link MeshAiProperties} bound it, and the reason the override here is deliberately unlike
 * every default. Asserting the default proves nothing, because the field initialiser produces
 * the same value whether or not the YAML is read at all.
 */
@SpringBootTest(properties = {
        "mesh.tenant.allow-all=true",
        "mesh.ai.semantic.top-k=7",
        "mesh.ai.semantic.minimum-score=0.35",
        "mesh.ai.semantic.model-dir=custom/model/path"
})
class SemanticPropertiesTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SemanticProperties properties;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("mesh.ai.semantic is registered as a bean")
    void propertiesAreABean() {
        assertThat(context.getBeanNamesForType(SemanticProperties.class)).hasSize(1);
    }

    @Test
    @DisplayName("top-k binds, and does not fall back to the field default")
    void topKBinds() {
        assertThat(properties.getTopK())
                .as("Sticking at 20 means the YAML was not read - which is exactly what a "
                        + "mis-indented block looks like from inside the JVM")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("minimum-score binds")
    void minimumScoreBinds() {
        assertThat(properties.getMinimumScore()).isEqualTo(0.35d);
    }

    @Test
    @DisplayName("model-dir binds")
    void modelDirBinds() {
        assertThat(properties.getModelDir()).isEqualTo("custom/model/path");
    }

    @Test
    @DisplayName("the nomic task prefixes are present, because omitting them degrades results silently")
    void taskPrefixesAreSet() {
        // These do not error when absent - they just make retrieval quietly worse. A test that
        // asserts them is the only thing that catches their removal.
        assertThat(properties.getDocumentPrefix()).isEqualTo("search_document: ");
        assertThat(properties.getQueryPrefix()).isEqualTo("search_query: ");
    }

    @Test
    @DisplayName("the block is nested under mesh.ai, not a sibling of it")
    void blockIsNestedUnderAi() {
        // The direct assertion of the bug that prompted this class. If the YAML is ever
        // mis-indented again, this fails even though the properties above still bind their
        // defaults - which is precisely how the original breakage hid.
        assertThat(environment.getProperty("mesh.ai.semantic.model-dir")).isNotNull();
        assertThat(environment.getProperty("mesh.semantic.model-dir"))
                .as("mesh.semantic.* is not a bound prefix. If this resolves, the YAML has been "
                        + "mis-indented and mesh.ai.semantic is silently using defaults")
                .isNull();
    }

    }