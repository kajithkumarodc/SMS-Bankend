package com.smsapp.face;

import com.smsapp.common.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Face embeddings as bytes, and the similarity between them.
 *
 * <p>Stored as {@code bytea} rather than a pgvector column on purpose. Matching is
 * always scoped to one section's roster -- about 40 embeddings -- so the scan below
 * is microseconds, and even a whole-school scan is a few thousand vectors. Requiring
 * the {@code vector} extension would pin the project to a non-stock Postgres image
 * for a search problem far too small to need an index. If that ever changes (millions
 * of vectors, cross-school search), this is the one class that has to move.
 *
 * <p>Layout is little-endian float32, which is what ONNX Runtime and NumPy both hand
 * back, so the sidecar can send its output with no conversion.
 */
public final class Embeddings {

    /** ArcFace / InsightFace buffalo_l output size. Not a hard limit -- see {@code dimensions}. */
    public static final int DEFAULT_DIMENSIONS = 512;

    private static final int BYTES_PER_FLOAT = 4;

    private Embeddings() {
    }

    /** Packs a raw embedding into its stored form. */
    public static byte[] toBytes(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * BYTES_PER_FLOAT)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : embedding) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * Unpacks a stored embedding.
     *
     * @throws ApiException 500 if the stored bytes are not a whole number of floats --
     *         that means the column was written by something other than this class,
     *         which is a bug rather than bad input.
     */
    public static float[] fromBytes(byte[] stored) {
        if (stored.length % BYTES_PER_FLOAT != 0) {
            throw new ApiException("Stored embedding is malformed", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        ByteBuffer buffer = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN);
        float[] embedding = new float[stored.length / BYTES_PER_FLOAT];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }

    /**
     * Cosine similarity, in [-1, 1] -- higher is more alike. This is the number the
     * match thresholds are expressed against.
     *
     * <p>Not assumed to be operating on normalised vectors: ArcFace output usually is,
     * but dividing by the magnitudes costs nothing at this scale and makes the function
     * correct for any input rather than only for well-behaved input.
     *
     * @throws ApiException 500 if the two embeddings are different lengths, which means
     *         two different models' output got compared -- exactly what model_version
     *         exists to prevent.
     */
    public static double cosineSimilarity(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new ApiException("Cannot compare embeddings of different sizes",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        double dot = 0;
        double leftMagnitude = 0;
        double rightMagnitude = 0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftMagnitude += (double) left[i] * left[i];
            rightMagnitude += (double) right[i] * right[i];
        }

        // A zero vector has no direction, so "how similar" has no answer. Report it as
        // maximally dissimilar rather than dividing by zero and yielding NaN, which
        // would silently sort as neither above nor below any threshold.
        if (leftMagnitude == 0 || rightMagnitude == 0) {
            return -1;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
