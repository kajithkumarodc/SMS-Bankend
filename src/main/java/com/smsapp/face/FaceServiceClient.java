package com.smsapp.face;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smsapp.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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

    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();
    private static final String GENERIC_REJECTION = "the image was rejected";

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

        // The part needs BOTH a filename and its own Content-Type. Without the filename
        // FastAPI does not treat it as an UploadFile and reports the field as missing
        // entirely; without the part Content-Type the sidecar's allow-list rejects it.
        // FormHttpMessageConverter takes the filename from Resource#getFilename, and the
        // per-part Content-Type from the wrapping HttpEntity's headers -- hence both here.
        // (MultipartBodyBuilder would be tidier but needs reactive-streams, which this
        // non-reactive app does not ship.)
        String partFilename = filename == null || filename.isBlank() ? "upload" : filename;
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return partFilename;
            }
        }, partHeaders));

        try {
            return client.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
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

    /**
     * Pulls FastAPI's {@code {"detail": "..."}} message out of an error body.
     *
     * <p>Parsed properly rather than scanned for a substring: starlette serialises with a
     * space after the colon, so a literal {@code "detail":"} search silently missed every
     * message and replaced it with the generic fallback -- defeating the whole point of
     * relaying the sidecar's actionable text to whoever uploaded the photo.
     */
    private static String detailFrom(java.io.InputStream body) {
        try {
            JsonNode detail = ERROR_MAPPER.readTree(body).get("detail");
            if (detail == null) return GENERIC_REJECTION;
            // FastAPI sends a string for an explicit HTTPException, but an array of
            // per-field objects for its own request-validation failures.
            String message = detail.isTextual() ? detail.asText() : detail.toString();
            return message.isBlank() ? GENERIC_REJECTION : message;
        } catch (Exception unreadable) {
            return GENERIC_REJECTION;
        }
    }
}
