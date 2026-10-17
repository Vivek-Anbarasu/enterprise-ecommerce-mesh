package com.enterprise.mesh.ai.semantic;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * nomic-embed-text in-process, via ONNX Runtime and the HuggingFace tokenizer.
 *
 * <p><b>No network, no GPU, no service.</b> The model is ~137M parameters and returns in
 * milliseconds on CPU, so a hosted endpoint would add a network hop, a deployment, an outage
 * mode and a bill, in exchange for latency this workload does not have. ONNX Runtime also
 * gives an OpenAI-compatible-free local API with no batching concerns at this request volume.
 *
 * <p><b>Lazy initialisation.</b> The session is opened on first use rather than at startup,
 * because loading ~270MB of weights into a constructor blocks the service's readiness probe for
 * no benefit - a service whose only AI feature is search should still be able to serve
 * recommendations while the model is loading, or on a pod that never receives a search at all.
 * {@link #isAvailable()} therefore reports file presence, not session readiness, so callers see
 * "unavailable" rather than a first-request stall.
 *
 * <p><b>Input names are inspected, not assumed.</b> Different ONNX exports of the same model
 * disagree about whether {@code token_type_ids} exists. Supplying an input the graph does not
 * declare fails the run outright, and a missing one is a validation error in the graph, so the
 * set is derived from the session rather than hardcoded.
 */
@Component
@Slf4j
public class OnnxTextEmbedder implements TextEmbedder {

    private final SemanticProperties properties;

    private final Object sessionLock = new Object();

    /** Non-null once loaded. Guarded by {@link #sessionLock}. */
    private OrtSessionHandle loaded;

    private volatile String loadFailure;

    public OnnxTextEmbedder(SemanticProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        Path dir = properties.resolveModelDir();
        if (dir == null || !Files.isRegularFile(dir.resolve(SemanticProperties.ONNX_FILE))
                || !Files.isRegularFile(dir.resolve(SemanticProperties.TOKENIZER_FILE))) {
            return false;
        }
        // A previous load that threw is not retryable per request; re-report the reason
        // rather than re-paying the failed-open cost on every search.
        return loadFailure == null;
    }

    @Override
    public int dimensions() {
        // nomic-embed-text is 768-wide. Reported as a constant so callers can size result
        // handling without loading the model; a mismatch is caught by the dimension check
        // in search, not here.
        return 768;
    }

    @Override
    public float[] embedDocument(String text) {
        return embed(text, properties.getDocumentPrefix());
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text, properties.getQueryPrefix());
    }

    private float[] embed(String text, String prefix) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Cannot embed blank text");
        }
        OrtSessionHandle handle = load();
        return handle.embed(prefix + text);
    }

    /**
     * Opens the model once.
     *
     * <p>Synchronised because concurrent first requests would otherwise each open a session and
     * leak all but one. {@link #isAvailable()} has already established that the files exist, so
     * anything thrown here is a real load failure - a corrupt export, a missing tokenizer, a
     * missing native library - and is remembered rather than retried per request.
     */
    private OrtSessionHandle load() {
        OrtSessionHandle existing = loaded;
        if (existing != null) {
            return existing;
        }
        synchronized (sessionLock) {
            if (loaded != null) {
                return loaded;
            }
            if (loadFailure != null) {
                throw new IllegalStateException("Embedding model is not loadable: " + loadFailure);
            }
            try {
                loaded = OrtSessionHandle.open(properties.resolveModelDir(), properties);
            } catch (IOException | RuntimeException | ai.onnxruntime.OrtException e) {
                loadFailure = e.toString();
                log.error("Failed to load embedding model from {}. Semantic search will report "
                        + "unavailable for the life of this process; the model directory must be "
                        + "fixed and the pod restarted.", properties.getModelDir(), e);
                throw new IllegalStateException("Embedding model is not loadable: " + loadFailure, e);
            }
            return loaded;
        }
    }

    @PreDestroy
    void close() {
        OrtSessionHandle handle = loaded;
        if (handle != null) {
            handle.close();
        }
    }

    /** A loaded tokenizer plus ONNX session. One per embedder; {@code run} is thread-safe. */
    private static final class OrtSessionHandle implements AutoCloseable {

        private final ai.onnxruntime.OrtEnvironment environment;
        private final ai.onnxruntime.OrtSession session;
        private final HuggingFaceTokenizer tokenizer;
        private final Set<String> inputNames;

        private OrtSessionHandle(ai.onnxruntime.OrtEnvironment environment,
                                 ai.onnxruntime.OrtSession session,
                                 HuggingFaceTokenizer tokenizer,
                                 Set<String> inputNames) {
            this.environment = environment;
            this.session = session;
            this.tokenizer = tokenizer;
            this.inputNames = inputNames;
        }

        static OrtSessionHandle open(Path dir, SemanticProperties properties)
                throws IOException, ai.onnxruntime.OrtException {
            if (dir == null) {
                throw new IOException("model-dir is not a valid path");
            }
            Path tokenizerPath = dir.resolve(SemanticProperties.TOKENIZER_FILE);
            if (!Files.isRegularFile(tokenizerPath)) {
                throw new IOException("No " + SemanticProperties.TOKENIZER_FILE + " in " + dir);
            }

            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

            ai.onnxruntime.OrtEnvironment environment = ai.onnxruntime.OrtEnvironment.getEnvironment();
            ai.onnxruntime.OrtSession.SessionOptions options =
                    new ai.onnxruntime.OrtSession.SessionOptions();
            // One search runs on one virtual thread; intra-op parallelism on top of that
            // oversubscribes the CPU and measures slower, not faster.
            options.setIntraOpNumThreads(1);
            ai.onnxruntime.OrtSession session = environment.createSession(
                    dir.resolve(SemanticProperties.ONNX_FILE).toString(), options);

            Set<String> declaredInputs = session.getInputNames();
            log.info("Loaded embedding model from {} (inputs: {})", dir, declaredInputs);
            return new OrtSessionHandle(environment, session, tokenizer, declaredInputs);
        }

        float[] embed(String text) {
            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            if (ids.length == 0) {
                throw new IllegalArgumentException("Text tokenised to nothing");
            }

            long[] shape = {1L, (long) ids.length};
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new HashMap<>();
            try {
                if (inputNames.contains("input_ids")) {
                    inputs.put("input_ids", tensor(ids, shape));
                }
                if (inputNames.contains("attention_mask")) {
                    inputs.put("attention_mask", tensor(mask, shape));
                }
                if (inputNames.contains("token_type_ids")) {
                    // Single-segment input, so every token is segment 0.
                    inputs.put("token_type_ids", tensor(new long[ids.length], shape));
                }
                if (inputs.isEmpty()) {
                    throw new IllegalStateException(
                            "Model declares no recognisable text inputs: " + inputNames);
                }

                try (ai.onnxruntime.OrtSession.Result result = session.run(inputs)) {
                    return pool(result.get(0), mask);
                }
            } catch (ai.onnxruntime.OrtException e) {
                // OrtException is checked and covers everything from a shape mismatch to a
                // corrupt weight. Callers of TextEmbedder deal in unchecked exceptions, and
                // a failed inference is an environment fault rather than a bad argument -
                // so it must not surface as something a caller could be tempted to retry.
                throw new IllegalStateException("Embedding inference failed: " + e.getMessage(), e);
            } finally {
                closeAll(inputs);
            }
        }

        private ai.onnxruntime.OnnxTensor tensor(long[] data, long[] shape)
                throws ai.onnxruntime.OrtException {
            return ai.onnxruntime.OnnxTensor.createTensor(environment, LongBuffer.wrap(data), shape);
        }

        /**
         * Handles both ONNX export conventions.
         *
         * <p>Some exports of this model emit the pooled and normalised sentence vector directly
         * ({@code [batch][hidden]}); others emit per-token vectors ({@code [batch][seq][hidden]})
         * and leave pooling to the caller. Guessing wrong here yields either an index error or a
         * silently meaningless vector, so the rank is checked rather than assumed.
         */
        private float[] pool(ai.onnxruntime.OnnxValue output, long[] mask)
                throws ai.onnxruntime.OrtException {
            Object value = output.getValue();

            if (value instanceof float[][] alreadyPooled) {
                // [batch][hidden]: the export did the pooling itself. Normalise in place
                // so cosine still reduces to a dot product.
                float[] vector = alreadyPooled[0];
                Vectors.l2NormalizeInPlace(vector);
                return vector;
            }

            if (value instanceof float[][][] tokenLevel) {
                // [batch][seq][hidden]: pooling over unpadded tokens is ours to do.
                float[] pooled = Vectors.maskedMeanPool(tokenLevel[0], mask);
                Vectors.l2NormalizeInPlace(pooled);
                return pooled;
            }

            throw new IllegalStateException(
                    "Expected a float embedding from the model, got " + value.getClass().getName()
                            + ". A non-float output means the export is quantised; re-export as fp32.");
        }

        private void closeAll(Map<String, ai.onnxruntime.OnnxTensor> tensors) {
            tensors.values().forEach(tensor -> {
                try {
                    tensor.close();
                } catch (Exception e) {
                    log.debug("Ignoring tensor close failure", e);
                }
            });
        }

        @Override
        public void close() {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Ignoring ONNX session close failure", e);
            }
            try {
                tokenizer.close();
            } catch (Exception e) {
                log.warn("Ignoring tokenizer close failure", e);
            }
        }
    }
}