package com.enterprise.mesh.ai.semantic;

/**
 * Turns text into a vector for similarity search.
 *
 * <p>An interface rather than a concrete class for the same reason
 * {@link com.enterprise.mesh.ai.service.RecommendationService#rank} is a pure function: the
 * interesting logic - pooling, normalisation, cosine - is worth testing without a 270MB model
 * on disk, and worth being able to swap an encoder without touching the search path.
 *
 * <p>Implementations must return L2-normalised vectors. Cosine similarity is then a dot product,
 * and {@link Vectors#cosine} relies on that.
 */
public interface TextEmbedder {

    /**
     * Whether embeddings can actually be produced.
     *
     * <p>False when the model is not loaded. Callers must check this and say so rather than
     * returning an empty result set, because "search is unavailable" and "nothing matched" are
     * different answers and a shopper should only be told the second one when it is true.
     */
    boolean isAvailable();

    /** Embedding width. Constant for a given model. */
    int dimensions();

    /**
     * Embeds a catalogue product.
     *
     * @throws IllegalStateException if {@link #isAvailable()} is false
     */
    float[] embedDocument(String text);

    /**
     * Embeds a shopper's query.
     *
     * @throws IllegalStateException if {@link #isAvailable()} is false
     */
    float[] embedQuery(String text);
}