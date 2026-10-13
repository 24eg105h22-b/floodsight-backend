package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.exception.*;
import com.flooddepth.scene.SceneTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.flooddepth.exception.GeminiFailureException.Code.*;
import static com.flooddepth.ai.GeminiFloodVisionClient.DiagnosticStage.*;

class GeminiDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String KEY = "unit-test-private-key";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GeminiFloodVisionClient client = new GeminiFloodVisionClient(builder.build(),
            new AiResponseParser(mapper), KEY, "gemini-2.5-flash");

    @ParameterizedTest
    @CsvSource({"401,GEMINI_AUTH_FAILURE", "403,GEMINI_AUTH_FAILURE", "404,GEMINI_MODEL_NOT_FOUND",
            "429,GEMINI_RATE_LIMIT", "400,GEMINI_PROVIDER_4XX", "500,GEMINI_PROVIDER_5XX", "503,GEMINI_PROVIDER_5XX"})
    void categorizesHttpWithoutLeakingProviderBodyOrCause(int status, GeminiFailureException.Code code) {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.valueOf(status)).body(KEY + " PRIVATE_BODY"));
        var error = assertThrows(GeminiFailureException.class, () -> client.analyzeSceneAndDepth(new byte[]{1}, "image/png", new byte[]{2}, "image/png"));
        assertEquals(code, error.code()); assertEquals(status, error.providerHttpStatus());
        assertTrue(error.responseBodyPresent()); assertNull(error.getCause());
        assertFalse(error.getMessage().contains(KEY)); assertFalse(error.getMessage().contains("PRIVATE_BODY"));
        server.verify();
    }

    @Test
    void invalidKeyReportedAs400IsAuthFailureAndLogsOnlyAllowlistedMetadata() {
        Logger logger = (Logger) LoggerFactory.getLogger(GeminiFloodVisionClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            server.expect(request -> {}).andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body(
                    "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"" + KEY
                    + " PRIVATE_BODY Authorization bearer-secret MAPILLARY-secret\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"));
            var result = client.diagnose(TEXT, null, null);
            assertFalse(result.success()); assertEquals("GEMINI_AUTH_FAILURE", result.diagnosticCode());
            assertEquals("API_KEY_INVALID", result.providerCode());
            assertEquals(400, result.providerHttpStatus()); assertFalse(result.jsonParsed());
            String output = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertTrue(output.contains("API_KEY_INVALID")); assertTrue(output.contains("httpStatus=400"));
            assertTrue(output.contains("model=gemini-2.5-flash")); assertTrue(output.contains("durationMs="));
            for (String secret : List.of(KEY, "PRIVATE_BODY", "bearer-secret", "MAPILLARY-secret")) assertFalse(output.contains(secret));
            assertTrue(logs.list.stream().allMatch(log -> log.getThrowableProxy() == null));
            server.verify();
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @Test
    void arbitraryProviderCodeIsNotRetained() {
        server.expect(request -> {}).andRespond(withServerError().body("{\"error\":{\"status\":\"" + KEY + "\"}}"));
        var error = assertThrows(GeminiFailureException.class, () -> client.analyzeSceneAndDepth(new byte[]{1}, "image/png", new byte[]{2}, "image/png"));
        assertNull(error.providerCode());
    }

    @Test
    void timeoutAndNetworkAreDistinctAndNeverRetryOrKeepSensitiveCause() {
        server.expect(request -> {}).andRespond(withException(new java.net.SocketTimeoutException(KEY)));
        server.expect(request -> {}).andRespond(withException(new java.net.UnknownHostException(KEY)));
        var timeout = client.diagnose(TEXT, null, null);
        var network = client.diagnose(TEXT, null, null);
        assertEquals("GEMINI_TIMEOUT", timeout.diagnosticCode()); assertEquals("GEMINI_NETWORK_ERROR", network.diagnosticCode());
        assertNull(timeout.providerHttpStatus()); assertNull(network.providerHttpStatus());
        assertFalse(timeout.responseBodyPresent()); assertFalse(timeout.toString().contains(KEY)); assertFalse(network.toString().contains(KEY));
        server.verify();
    }

    @Test
    void timeoutWhileReadingResponseBodyIsStillTimeout() {
        server.expect(request -> {}).andRespond(request -> new org.springframework.mock.http.client.MockClientHttpResponse(
                new java.io.InputStream() {
                    @Override public int read() throws java.io.IOException { throw new java.net.SocketTimeoutException(KEY); }
                }, HttpStatus.OK));
        var result = client.diagnose(TEXT, null, null);
        assertEquals("GEMINI_TIMEOUT", result.diagnosticCode());
        assertFalse(result.toString().contains(KEY)); server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "{}", "null", ""})
    void malformedProviderEnvelopeHasSuccessfulHttpButNoAcceptedContent(String body) {
        server.expect(request -> {}).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var result = client.diagnose(TEXT, null, null);
        assertEquals("GEMINI_MALFORMED_RESPONSE", result.diagnosticCode()); assertEquals(200, result.providerHttpStatus());
        assertFalse(result.success()); assertFalse(result.jsonParsed());
    }

    @ParameterizedTest
    @CsvSource({"'{broken',GEMINI_MALFORMED_RESPONSE", "'{\"confidence\":2}',GEMINI_RESPONSE_VALIDATION_FAILED"})
    void sceneSyntaxAndSchemaFailuresPreserveProviderSuccess(String payload, GeminiFailureException.Code code) throws Exception {
        server.expect(request -> {}).andRespond(withSuccess(envelope(payload), MediaType.APPLICATION_JSON));
        var error = assertThrows(GeminiFailureException.class, () -> client.analyzeSceneAndDepth(new byte[]{1}, "image/png", new byte[]{2}, "image/png"));
        assertEquals(code, error.code()); assertEquals(200, error.providerHttpStatus()); assertNull(error.getCause());
    }

    @Test
    void textUsesSameHeaderModelEndpointAndNoSchemaThenStructuredUsesSchema() throws Exception {
        server.expect(request -> {
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", request.getURI().toString());
            assertEquals(KEY, request.getHeaders().getFirst("x-goog-api-key"));
            try {
                String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                assertFalse(body.contains(KEY)); assertFalse(body.contains("inlineData"));
                assertFalse(mapper.readTree(body).has("generationConfig"));
            } catch (java.io.IOException exception) { throw new AssertionError(exception); }
        }).andRespond(withSuccess(envelope("{\"status\":\"ok\"}"), MediaType.APPLICATION_JSON));
        server.expect(request -> {
            try {
                var body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                assertEquals("application/json", body.path("generationConfig").path("responseMimeType").textValue());
                assertEquals("ok", body.path("generationConfig").path("responseSchema").path("properties").path("status").path("enum").get(0).textValue());
            } catch (java.io.IOException exception) { throw new AssertionError(exception); }
        }).andRespond(withSuccess(envelope("{\"status\":\"ok\"}"), MediaType.APPLICATION_JSON));
        for (var stage : List.of(TEXT, STRUCTURED)) {
            var result = client.diagnose(stage, null, null);
            assertTrue(result.success()); assertTrue(result.keyPresent()); assertTrue(result.providerRequestAttempted());
            assertEquals(200, result.providerHttpStatus()); assertTrue(result.jsonParsed()); assertTrue(result.schemaValid());
            assertFalse(result.markdownFencesPresent()); assertFalse(result.toString().contains(KEY));
        }
        server.verify();
    }

    @Test
    void fencesAreObservedButNotStrippedAndSchemaIsNeverWeakened() throws Exception {
        server.expect(request -> {}).andRespond(withSuccess(envelope("```json\n{\"status\":\"ok\"}\n```"), MediaType.APPLICATION_JSON));
        server.expect(request -> {}).andRespond(withSuccess(envelope("{\"status\":\"ok\",\"extra\":true}"), MediaType.APPLICATION_JSON));
        var fenced = client.diagnose(STRUCTURED, null, null);
        assertTrue(fenced.markdownFencesPresent()); assertFalse(fenced.jsonParsed());
        assertEquals("GEMINI_MALFORMED_RESPONSE", fenced.diagnosticCode());
        var invalid = client.diagnose(STRUCTURED, null, null);
        assertTrue(invalid.jsonParsed()); assertFalse(invalid.schemaValid());
        assertEquals("GEMINI_RESPONSE_VALIDATION_FAILED", invalid.diagnosticCode());
    }

    @Test
    void minimalImageUsesOnlyOneImageAndReturnsOnlyValidatedFields() throws Exception {
        byte[] image = SceneTestFixtures.png();
        server.expect(request -> {
            try {
                var parts = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString())
                        .path("contents").get(0).path("parts");
                assertEquals(2, parts.size());
                assertEquals("image/png", parts.get(1).path("inlineData").path("mimeType").textValue());
                assertArrayEquals(image, java.util.Base64.getDecoder().decode(parts.get(1).path("inlineData").path("data").textValue()));
                assertTrue(parts.get(0).path("text").textValue().contains("Do not estimate depth"));
            } catch (java.io.IOException exception) { throw new AssertionError(exception); }
        }).andRespond(withSuccess(envelope("{\"imageReceived\":true,\"sceneType\":\"flooded_road\"}"), MediaType.APPLICATION_JSON));
        var result = client.diagnose(IMAGE, image, "image/png");
        assertTrue(result.success()); assertEquals("flooded_road", result.sceneType());
        assertThrows(IllegalArgumentException.class, () -> client.diagnose(IMAGE, null, null)); server.verify();
    }

    @Test
    void validSceneStillUsesStrictParserAndAlwaysKeepsDepthFalse() throws Exception {
        server.expect(request -> {}).andRespond(withSuccess(envelope(com.flooddepth.depth.CombinedFixtures.overlapJson()), MediaType.APPLICATION_JSON));
        var result = client.analyzeSceneAndDepth(SceneTestFixtures.png(), "image/png", SceneTestFixtures.png(), "image/png");
        assertTrue(result.sceneMatch()); assertTrue(result.visualDepthEstimate().possible()); server.verify();
    }

    @Test void transport429RetainsOnlyTypedRetryMetadataAndFixedCode() {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","4")
            .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\""+KEY+" PRIVATE_BODY\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"5s\"}]}}"));
        var failure=assertThrows(GeminiFailureException.class,()->client.analyzeSceneAndDepth(new byte[]{1},"image/png",new byte[]{2},"image/png"));
        assertEquals(GEMINI_RATE_LIMIT_RETRYABLE,failure.code());assertEquals("RESOURCE_EXHAUSTED",failure.providerCode());
        assertEquals(4000L,failure.rateLimit().retryAfterMs());assertEquals(5000L,failure.rateLimit().retryDelayMs());
        assertFalse(failure.rateLimit().toString().contains(KEY));assertNull(failure.getCause());server.verify();
    }
    @Test void transport429DailyQuotaDoesNotBecomeRetryableFromRetryHeader() {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","4")
            .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"GenerateRequestsPerDayPerProject\"}]}]}}"));
        var failure=assertThrows(GeminiFailureException.class,()->client.analyzeSceneAndDepth(new byte[]{1},"image/png",new byte[]{2},"image/png"));
        assertEquals(GEMINI_QUOTA_EXHAUSTED,failure.code());assertFalse(failure.rateLimit().retryable());server.verify();
    }
    private String envelope(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content", Map.of("parts", List.of(Map.of("text", text)))))));
    }
}
