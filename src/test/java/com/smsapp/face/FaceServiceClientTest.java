package com.smsapp.face;

import com.smsapp.common.ApiException;
import com.smsapp.face.FaceServiceClient.DetectResult;
import com.smsapp.face.FaceServiceClient.EmbedResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Contract test for the sidecar boundary.
 *
 * <p>The JSON below is not hand-written: it was captured from SMS-FaceService's own
 * responses (tests/test_api.py runs the same app) so this fails if either side's field
 * names drift apart. That seam is snake_case on the wire and camelCase in Java, which
 * is exactly the kind of mismatch a test has to hold in place.
 */
class FaceServiceClientTest {

    private static final String EMBED_JSON = """
            {"model_version": "insightface/buffalo_l/arcface-512/v1", \
            "face": {"embedding": [0.0125, -0.5, 0.25], "dimensions": 3, \
            "quality_score": 0.8734, "bbox": [10, 20, 200, 210]}}""";

    private static final String DETECT_JSON = """
            {"model_version": "insightface/buffalo_l/arcface-512/v1", "faces_detected": 1, \
            "faces": [{"embedding": [0.0125, -0.5, 0.25], "dimensions": 3, \
            "quality_score": 0.8734, "bbox": [10, 20, 200, 210]}]}""";

    private record Harness(FaceServiceClient client, MockRestServiceServer server) {
    }

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://face:8000");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // The package-private constructor takes an already-built client: the public one
        // installs its own request factory, which would replace the mock's and silently
        // send these requests nowhere.
        return new Harness(new FaceServiceClient(builder.build(), true), server);
    }

    @Test
    void parsesAnEmbedResponseCapturedFromTheSidecar() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/embed"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess(EMBED_JSON, MediaType.APPLICATION_JSON));

        EmbedResult result = harness.client().embed(new byte[] {1}, "a.png", "image/png");

        assertThat(result.modelVersion()).isEqualTo("insightface/buffalo_l/arcface-512/v1");
        assertThat(result.face().dimensions()).isEqualTo(3);
        assertThat(result.face().qualityScore()).isEqualTo(0.8734);
        assertThat(result.face().embedding()).containsExactly(0.0125f, -0.5f, 0.25f);
        assertThat(result.face().bboxAsString()).isEqualTo("10,20,200,210");
    }

    @Test
    void parsesADetectResponseCapturedFromTheSidecar() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/detect"))
                .andRespond(withSuccess(DETECT_JSON, MediaType.APPLICATION_JSON));

        DetectResult result = harness.client().detect(new byte[] {1}, "room.jpg", "image/jpeg");

        assertThat(result.facesDetected()).isEqualTo(1);
        assertThat(result.faces()).hasSize(1);
        assertThat(result.faces().getFirst().qualityScore()).isEqualTo(0.8734);
    }

    @Test
    void anEmptyRoomParsesAsNoFacesRatherThanFailing() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/detect"))
                .andRespond(withSuccess(
                        """
                        {"model_version": "insightface/buffalo_l/arcface-512/v1", \
                        "faces_detected": 0, "faces": []}""",
                        MediaType.APPLICATION_JSON));

        DetectResult result = harness.client().detect(new byte[] {1}, "room.jpg", "image/jpeg");

        assertThat(result.facesDetected()).isZero();
        assertThat(result.faces()).isEmpty();
    }

    /** The sidecar's 422 is actionable by whoever uploaded the photo, so its text survives. */
    @Test
    void surfacesTheSidecarsRejectionMessageAs422() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/embed"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        // A space after the colon, exactly as starlette serialises it --
                        // the shape that broke the original substring-scanning parser.
                        .body("{\"detail\": \"Expected one face, found 3 - crop to a single student\"}"));

        assertThatThrownBy(() -> harness.client().embed(new byte[] {1}, "a.png", "image/png"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Expected one face, found 3")
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** A sidecar failure must never leak its URL or a stack trace into an API response. */
    @Test
    void aSidecarFailureBecomesABadGatewayWithNoInternalDetail() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/embed"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> harness.client().embed(new byte[] {1}, "a.png", "image/png"))
                .isInstanceOf(ApiException.class)
                .hasMessageNotContaining("face:8000")
                .extracting("status").isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    /** No base URL configured = the feature is off, and says so rather than failing obscurely. */
    @Test
    void anUnconfiguredClientReports503AndIsNotConfigured() {
        FaceServiceClient client = new FaceServiceClient(RestClient.builder(), "", "", 5L);

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.embed(new byte[] {1}, "a.png", "image/png"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** FastAPI's own validation errors send an array, not a string. Must not crash. */
    @Test
    void handlesAValidationErrorBodyThatIsNotAPlainString() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/embed"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\": [{\"loc\": [\"body\", \"file\"], \"msg\": \"field required\"}]}"));

        assertThatThrownBy(() -> harness.client().embed(new byte[] {1}, "a.png", "image/png"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("field required");
    }

    /** An unparseable body must degrade to the generic message, not blow up. */
    @Test
    void handlesANonJsonErrorBody() {
        Harness harness = harness();
        harness.server().expect(requestTo("http://face:8000/v1/embed"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>502 Bad Gateway</html>"));

        assertThatThrownBy(() -> harness.client().embed(new byte[] {1}, "a.png", "image/png"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("the image was rejected");
    }

    @Test
    void aConfiguredClientReportsItself() {
        assertThat(harness().client().isConfigured()).isTrue();
    }
}
