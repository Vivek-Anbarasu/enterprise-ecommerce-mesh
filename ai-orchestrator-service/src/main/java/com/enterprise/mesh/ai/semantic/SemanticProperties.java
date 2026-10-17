package com.enterprise.mesh.ai.semantic;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Semantic search settings, bound from {@code mesh.ai.semantic.*}.
 *
 * <p>Separate from {@link com.enterprise.mesh.ai.config.MeshAiProperties} rather than folded into
 * it, because the two answer different questions. That class asks "which model provider
 * ranks recommendations", which is currently {@code none} and will eventually name a
 * generative provider. This one asks "which embedding model backs search", which is a
 * different kind of thing: an encoder has no notion of a provider, and conflating them would
 * mean a future ranking provider could not be set without also implying a different search
 * model.
 *
 * <p><b>The model is a file, not a dependency.</b> nomic-embed-text is ~270MB of ONNX plus a
 * tokenizer. That does not belong in a jar, so {@link #modelDir} points at a directory
 * containing {@code model.onnx} and {@code tokenizer.json} - mounted from a volume, baked
 * into the image, or downloaded by an init container. The service starts without it; see
 * {@link #resolveModelDir} for what happens then.
 */
@Validated
@ConfigurationProperties(prefix = "mesh.ai.semantic")
@Getter
@Setter
@Slf4j
public class SemanticProperties {

    /**
     * Model directory containing {@code model.onnx} and {@code tokenizer.json}.
     *
     * <p>Must contain the nomic task prefixes, which are part of the model's contract rather
     * than decoration - see {@link #documentPrefix} and {@link #queryPrefix}.
     */
    @NotBlank
    private String modelDir = "models/nomic-embed-text";

    /**
     * How many products a search returns.
     *
     * <p>Bounded because every hit costs a catalogue lookup for the storefront to render, and
     * because a search result page nobody scrolls past is wasted serialisation.
     */
    @Positive
    private int topK = 20;

    /**
     * Results scoring below this are dropped.
     *
     * <p>Cosine similarity between arbitrary product text and a shopper's query is frequently
     * near zero, and a list of everything that scored 0.1 is not a result set. The default is
     * deliberately permissive: the honest response to "nothing matched" is an empty list, not a
     * ranked list of noise, so this only removes the tail.
     *
     * <p>Bounded by the range of cosine similarity. An earlier {@code @Min(1)} here rejected
     * this property's own default of {@code 0.2}, which failed validation at startup and took
     * the entire service with it.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumScore = 0.2d;

    /**
 * Identifies the encoder in every stored vector.
 *
 * <p>Recorded per document rather than assumed, because two encoders' output is not comparable.
 * Swapping the model file without clearing the collection would leave old and new vectors
 * interleaved and produce confidently wrong results rather than an error.
 */
    @NotBlank
    private String modelId = "nomic-embed-text";

    /**
     * nomic-embed-text's document task prefix.
     *
     * <p>Not optional and not cosmetic. The model was trained with task prefixes, and querying
     * without them measurably degrades retrieval: the query and the document are embedded into
     * different regions of the space and similarity between them collapses. The failure is
     * silent - results are returned, they are just worse - so it is easy to ship without
     * noticing.
     */
    @NotBlank
    private String documentPrefix = "search_document: ";

    /**
     * nomic-embed-text's query task prefix. See {@link #documentPrefix}.
     */
    @NotBlank
    private String queryPrefix = "search_query: ";

    /**
     * Resolves the configured directory and reports whether it is actually usable.
     *
     * <p>Called at startup so a missing model is a log line an operator sees at deploy time
     * rather than a 500 the first shopper discovers. The service still starts: search degrades
     * to an explicit "unavailable", because a missing model must not take recommendations and
     * profile erasure down with it.
     */
    @PostConstruct
    void reportModelAvailability() {
        Path dir = resolveModelDir();

        if (dir == null || !Files.isDirectory(dir)) {
            log.warn("Semantic search disabled: model directory '{}' does not exist. Search "
                    + "will report unavailable; recommendations and profile erasure are "
                    + "unaffected. Mount the model or set mesh.ai.semantic.model-dir.",
                    modelDir);
            return;
        }
        if (!Files.isRegularFile(dir.resolve(ONNX_FILE))) {
            log.warn("Semantic search disabled: '{}' contains no {}. Search will report "
                    + "unavailable. Expected model.onnx and tokenizer.json in the directory.",
                    dir, ONNX_FILE);
            return;
        }
        log.info("Semantic search enabled: model-dir={} topK={} minimumScore={}",
                dir, topK, minimumScore);
    }

    static final String ONNX_FILE = "model.onnx";
    static final String TOKENIZER_FILE = "tokenizer.json";

    /** @return the model directory as a path, or {@code null} if it is blank or unresolvable */
    Path resolveModelDir() {
        if (modelDir == null || modelDir.isBlank()) {
            return null;
        }
        try {
            return Path.of(modelDir);
        } catch (java.nio.file.InvalidPathException e) {
            log.warn("mesh.ai.semantic.model-dir='{}' is not a valid path: {}", modelDir, e.toString());
            return null;
        }
    }
}