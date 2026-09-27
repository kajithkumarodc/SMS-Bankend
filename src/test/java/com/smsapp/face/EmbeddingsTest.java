package com.smsapp.face;

import com.smsapp.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EmbeddingsTest {

    @Test
    void roundTripsAnEmbeddingThroughItsStoredForm() {
        float[] original = {0.1f, -0.25f, 0.5f, 1.0f, -1.0f, 0f};

        float[] restored = Embeddings.fromBytes(Embeddings.toBytes(original));

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void storesFourBytesPerDimension() {
        assertThat(Embeddings.toBytes(new float[Embeddings.DEFAULT_DIMENSIONS]))
                .hasSize(Embeddings.DEFAULT_DIMENSIONS * 4);
    }

    @Test
    void anIdenticalEmbeddingScoresOne() {
        float[] embedding = {0.3f, 0.4f, -0.5f};

        assertThat(Embeddings.cosineSimilarity(embedding, embedding)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void anOppositeEmbeddingScoresMinusOne() {
        assertThat(Embeddings.cosineSimilarity(new float[] {1, 2, 3}, new float[] {-1, -2, -3}))
                .isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void anOrthogonalEmbeddingScoresZero() {
        assertThat(Embeddings.cosineSimilarity(new float[] {1, 0}, new float[] {0, 1}))
                .isCloseTo(0.0, within(1e-9));
    }

    /** Magnitude must not affect the score -- only direction carries identity. */
    @Test
    void scaleDoesNotChangeTheScore() {
        float[] embedding = {0.3f, 0.4f, -0.5f};
        float[] scaled = {3f, 4f, -5f};

        assertThat(Embeddings.cosineSimilarity(embedding, scaled)).isCloseTo(1.0, within(1e-6));
    }

    /**
     * A zero vector has no direction, so similarity has no answer. It must report
     * maximally dissimilar rather than NaN, which would sort neither above nor below
     * any threshold and so would silently pass every comparison.
     */
    @Test
    void aZeroEmbeddingIsMaximallyDissimilarRatherThanNaN() {
        double score = Embeddings.cosineSimilarity(new float[] {0, 0, 0}, new float[] {1, 2, 3});

        assertThat(score).isEqualTo(-1.0);
        assertThat(Double.isNaN(score)).isFalse();
    }

    /** Guards against comparing two different models' output -- what model_version prevents. */
    @Test
    void comparingDifferentSizesIsAnInternalError() {
        assertThatThrownBy(() -> Embeddings.cosineSimilarity(new float[] {1, 2}, new float[] {1, 2, 3}))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void malformedStoredBytesAreAnInternalError() {
        assertThatThrownBy(() -> Embeddings.fromBytes(new byte[] {1, 2, 3}))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
