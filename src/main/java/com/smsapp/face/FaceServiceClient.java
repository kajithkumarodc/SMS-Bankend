package com.smsapp.face;

import com.smsapp.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Talks to the SMS-FaceService sidecar (see that project's README).
 *
 * <p>The sidecar is stateless and knows nothing about students or consent -- it turns
 * image bytes into vectors. Every decision that matters stays on this side, so this
 * class is deliberately thin: no thresholds, no persistence, no retries that could
 * turn one enrolment into several.
 *
 * <p>Reached over Railway's private network. If {@code app.face.base-url} is unset the
 * feature is simply off, and callers get a 503 telling them so rather than a confusing
 * connection error.
 */
@Component
public class FaceServiceClient {

    /** One detected face as the sidecar reports it. */
    public record DetectedFace(
            float[] embedding,
            int dimensions,
            double qualityScore,
            List<Integer> bbox) {

        /** "x,y,w,h" for the bbox column, or null when the sidecar sent no box. */
        public String bboxAsString() {
            return bbox == null || bbox.size() != 4
                    ? null
                    : "%d,%d,%d,%d".formatted(bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3));
        }
    }

    public record EmbedResult(String modelVersion, DetectedFace face) {
    }

    public record DetectResult(String modelVersion, int facesDetected, List<DetectedFace> faces) {
    }

    /** Wire shapes, matching app/models.py on the sidecar. */
    private record WireFace(float[] embedding, int dimensions, double quality_score, List<Integer> bbox) {
        DetectedFace toDetected() {
            return new DetectedFace(embedding, dimensions, quality_score, bbox);
        }
    }

    private record WireEmbed(String model_version, WireFace face) {
    }

    private record WireDetect(String model_version, int faces_detected, List<WireFace> faces) {
    }

    private final RestClient client;
    private final boolean configured;

    // Explicit, because this class has two constructors: Spring only auto-selects when
    // there is exactly one, and without this it looks for a no-arg constructor and the
    // whole context fails to start.
    @Autowired
    public FaceServiceClient(RestClient.Builder builder,
                             @Value("${app.face.base-url:}") String baseUrl,
                             @Value("${app.face.api-key:}") String apiKey,
                             @Value("${app.face.timeout-seconds:30}") long timeoutSeconds) {
        this(build(builder, baseUrl, apiKey, timeoutSeconds), baseUrl != null && !baseUrl.isBlank());
    }

    /**
     * Takes an already-built client, so a test can supply one bound to a mock server.
     * The public constructor installs its own request factory, which would otherwise
     * replace -- and silently disable -- any test double.
     */
    FaceServiceClient(RestClient client, boolean configured) {
        this.client = client;
        this.configured = configured;
    }

    private static RestClient build(RestClient.Builder builder, String baseUrl, String apiKey,
                                    long timeoutSeconds) {
        // Model inference on CPU is slow enough that a default read timeout would fire
        // mid-request; a classroom photo at det_size 1024 can take several seconds. The
        // connect timeout stays short, because an unreachable sidecar should fail fast.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        boolean hasBaseUrl = baseUrl != null && !baseUrl.isBlank();
        RestClient.Builder configured = builder
                .requestFactory(requestFactory)
                // An unconfigured client never sends anything -- post() refuses first --
                // so this placeholder exists only to keep the builder valid.
                .baseUrl(hasBaseUrl ? baseUrl : "http://face-service.invalid");
        if (apiKey != null && !apiKey.isBlank()) {
            configured = configured.defaultHeader("X-Face-Api-Key", apiKey);
        }
        return configured.build();
    }

    /** False when no sidecar is configured, so callers can refuse early with a clear message. */
    public boolean isConfigured() {
        return configured;
    }

    /**
     * One portrait in, one embedding out. The sidecar rejects an image containing more
     * than one face rather than guessing which is the subject.
     *
     * @throws ApiException 503 if the sidecar is unconfigured or unreachable; 422 with
     *         the sidecar's own message when the photo is unusable (no face, several
     *         faces), because that is something the uploader can actually fix.
     */
    public EmbedResult embed(byte[] image, String filename, String contentType) {
        WireEmbed response = post("/v1/embed", image, filename, contentType, WireEmbed.class);
        return new EmbedResult(response.model_version(), response.face().toDetected());
    }

    /**
     * A classroom photo in, every face out. An empty list is a valid answer -- an empty
     * or unreadably dark room is not an error.
     */
    public DetectResult detect(byte[] image, String filename, String contentType) {
        WireDetect response = post("/v1/detect", image, filename, contentType, WireDetect.class);
        return new DetectResult(
                response.model_version(),
                response.faces_detected(),
                response.faces() == null ? List.of() : response.faces().stream().map(WireFace::toDetected).toList());
    }

    private <T> T post(String path, byte[] image, String filename, String contentType, Class<T> type) {
        if (!configured) {
            throw new ApiException("Face recognition is not configured on this deployment",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                // Multipart needs a filename for the part to be treated as a file; the
                // sidecar reads the content type, not the name.
                return filename == null || filename.isBlank() ? "upload" : filename;
            }
        });

        try {
            return client.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("X-Upload-Content-Type", contentType)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> status.value() == HttpStatus.UNPROCESSABLE_ENTITY.value(),
                            (request, response) -> {
                                // The sidecar's 422s are actionable by whoever uploaded the
                                // photo, so its message is surfaced rather than swallowed.
                                throw new ApiException(
                                        "That photo could not be used: " + detailFrom(response.getBody()),
                                        HttpStatus.UNPROCESSABLE_ENTITY);
                            })
                    .body(type);
        } catch (ApiException alreadyMapped) {
            throw alreadyMapped;
        } catch (RestClientException error) {
            // Never leak the sidecar's URL or stack into an API response.
            throw new ApiException("The face recognition service is unavailable. Please try again.",
                    HttpStatus.BAD_GATEWAY);
        }
    }

    private static String detailFrom(java.io.InputStream body) {
        try {
            String raw = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            // FastAPI reports {"detail": "..."}; fall back to the raw body if it does not.
            int marker = raw.indexOf("\"detail\":\"");
            if (marker < 0) return "the image was rejected";
            int start = marker + "\"detail\":\"".length();
            int end = raw.indexOf('"', start);
            return end < 0 ? "the image was rejected" : raw.substring(start, end);
        } catch (Exception readFailed) {
            return "the image was rejected";
        }
    }
}
