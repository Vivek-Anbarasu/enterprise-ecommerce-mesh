package com.enterprise.mesh.ai.semantic;

/**
 * The vector arithmetic behind semantic search, kept free of ONNX types so it can be tested
 * without a model on disk.
 *
 * <p>All methods are pure and allocation-light. They sit on the request path of every search, so
 * this is deliberately not doing anything clever.
 */
final class Vectors {

    private Vectors() {
    }

    /**
     * Averages token vectors, ignoring padding.
     *
     * <p>Pooling over padded positions would drag every vector toward the padding embedding and
     * compress the whole catalogue into a narrow band, which makes everything look similar to
     * everything else. The mask is what keeps a short description from embedding differently than
     * the same description with a different amount of padding around it.
     *
     * @param tokenVectors  {@code [sequence][hidden]}
     * @param attentionMask 1 for real tokens, 0 for padding
     * @return a new pooled vector, all-zero if the mask selects nothing
     */
    static float[] maskedMeanPool(float[][] tokenVectors, long[] attentionMask) {
        if (tokenVectors == null || tokenVectors.length == 0) {
            return new float[0];
        }
        int hidden = tokenVectors[0].length;
        float[] pooled = new float[hidden];

        int counted = 0;
        for (int position = 0; position < tokenVectors.length; position++) {
            if (position >= attentionMask.length || attentionMask[position] == 0L) {
                continue;
            }
            float[] vector = tokenVectors[position];
            for (int i = 0; i < hidden; i++) {
                pooled[i] += vector[i];
            }
            counted++;
        }

        if (counted == 0) {
            return pooled;
        }
        for (int i = 0; i < hidden; i++) {
            pooled[i] /= counted;
        }
        return pooled;
    }

    /**
     * Scales a vector to unit length, in place.
     *
     * <p>Unit vectors turn cosine similarity into a dot product, which is one multiply-add per
     * component rather than a square root and three passes over the data. On a brute-force scan
     * that is the difference between comfortable and noticeable.
     *
     * <p>A zero vector is left as-is rather than divided by zero: NaN propagates silently through
     * a sort comparator and produces an empty result set with no trace of why.
     */
    static void l2NormalizeInPlace(float[] vector) {
        double sumOfSquares = 0d;
        for (float component : vector) {
            sumOfSquares += component * component;
        }
        if (sumOfSquares == 0d) {
            return;
        }
        double inverse = 1d / Math.sqrt(sumOfSquares);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] * inverse);
        }
    }

    /**
     * Cosine similarity of two equal-length vectors.
     *
     * <p>Does not assume normalisation, so it is correct for stored vectors written by an older
     * version of this code that were not normalised. Returns 0 on a length mismatch rather than
     * throwing: a corrupt vector in the collection should cost that one product, not the query.
     */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0d || normB == 0d) {
            return 0d;
        }
        double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        // Rounding can push a self-comparison a hair past 1.0, which would then fail a
        // score-threshold comparison for no reason a caller could explain.
        return Math.max(-1d, Math.min(1d, similarity));
    }
}