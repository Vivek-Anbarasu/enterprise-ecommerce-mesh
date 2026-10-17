package com.enterprise.mesh.ai.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The vector arithmetic, tested without a model on disk.
 *
 * <p>This is the part of semantic search that is genuinely subtle and the part that cannot be
 * checked by looking at results. Everything here has a specific failure mode behind it: pooling
 * over padding compresses the whole catalogue into a narrow band, an unnormalised vector turns
 * cosine into a magnitude comparison, and floating point can push a self-comparison past 1.0.
 */
class VectorsTest {

    @Test
    @DisplayName("mean pooling averages the unpadded tokens only")
    void poolingIgnoresPadding() {
        // Two real tokens, then two padding positions left at zero. Averaging all four would
        // halve the result; averaging the two real ones must not.
        float[][] tokens = {
                {2f, 4f},
                {4f, 8f},
                {0f, 0f},
                {0f, 0f},
        };
        long[] mask = {1L, 1L, 0L, 0L};

        float[] pooled = Vectors.maskedMeanPool(tokens, mask);

        assertThat(pooled).containsExactly(3f, 6f);
    }

    @Test
    @DisplayName("an all-padding mask pools to zero rather than dividing by zero")
    void allPaddingDoesNotProduceNaN() {
        // NaN here would propagate silently through a sort comparator and produce an empty
        // result set with no trace of why.
        float[] pooled = Vectors.maskedMeanPool(new float[][]{{1f, 1f}}, new long[]{0L});

        assertThat(pooled).containsExactly(0f, 0f);
    }

    @Test
    @DisplayName("pooling an empty sequence returns an empty vector")
    void emptySequenceIsHandled() {
        assertThat(Vectors.maskedMeanPool(new float[0][], new long[0])).isEmpty();
    }

    @Test
    @DisplayName("normalisation produces a unit vector")
    void normalisationProducesUnitLength() {
        // 3-4-5 triangle, so the expected length is exactly 5.
        float[] vector = {3f, 4f};
        Vectors.l2NormalizeInPlace(vector);

        assertThat(vector[0]).isEqualTo(0.6f);
        assertThat(vector[1]).isEqualTo(0.8f);
    }

    @Test
    @DisplayName("a zero vector is left alone rather than becoming NaN")
    void zeroVectorIsNotDividedByZero() {
        float[] vector = {0f, 0f, 0f};
        Vectors.l2NormalizeInPlace(vector);

        assertThat(vector).containsExactly(0f, 0f, 0f);
    }

    @Test
    @DisplayName("cosine similarity of a vector with itself is exactly 1")
    void selfSimilarityIsOne() {
        // The rounding guard matters here: this value is compared against a score threshold,
        // so a self-comparison landing at 1.0000001 would fail for no reason a caller
        // could explain.
        assertThat(Vectors.cosine(new float[]{3f, 4f}, new float[]{3f, 4f})).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("orthogonal vectors score zero and opposite vectors score -1")
    void similarityBoundsAreCorrect() {
        assertThat(Vectors.cosine(new float[]{1f, 0f}, new float[]{0f, 1f})).isEqualTo(0.0d);
        assertThat(Vectors.cosine(new float[]{1f, 0f}, new float[]{-1f, 0f})).isEqualTo(-1.0d);
    }

    @Test
    @DisplayName("cosine ignores magnitude, so a long and a short vector of the same direction match")
    void similarityIgnoresMagnitude() {
        assertThat(Vectors.cosine(new float[]{1f, 2f}, new float[]{50f, 100f}))
                .as("This is the property that makes cosine the right measure here: a verbose "
                        + "and a terse description of the same product should match")
                .isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a length mismatch scores zero rather than throwing")
    void mismatchedWidthIsNotFatal() {
        // A corrupt or stale row in the collection should cost that one product, not the
        // whole query.
        assertThat(Vectors.cosine(new float[]{1f, 2f}, new float[]{1f, 2f, 3f})).isZero();
        assertThat(Vectors.cosine(null, new float[]{1f})).isZero();
        assertThat(Vectors.cosine(new float[0], new float[0])).isZero();
    }
}