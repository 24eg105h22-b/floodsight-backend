package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.depth.CombinedAnalysisPrompt;
import com.flooddepth.scene.SceneTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiContractDiagnosticsTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    final GeminiFloodVisionClient client = new GeminiFloodVisionClient(builder.build(), new AiResponseParser(mapper),
            "mock-contract-key", "gemini-3.8-flash");
    String envelope(String json) throws Exception {
        return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content", Map.of("parts", List.of(Map.of("text", json)))))));
    }
    @Test void exactModelMetadataIsOneGetWithoutGenerationOrListing() {
        server.expect(request -> {
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash", request.getURI().toString());
            assertEquals(org.springframework.http.HttpMethod.GET, request.getMethod());
            assertEquals("mock-contract-key",request.getHeaders().getFirst("x-goog-api-key"));
        }).andRespond(withSuccess("{\"name\":\"models/gemini-3.8-flash\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\",\"mock-contract-key\"]}", MediaType.APPLICATION_JSON));
        var result = client.modelMetadata(); assertTrue(result.success()); assertTrue(result.modelExists()); assertTrue(result.generateContentSupported());
        assertFalse(result.toString().contains("mock-contract-key")); server.verify();
    }
    @Test void metadataFailureNeverStartsTextOrImageGeneration() {
        server.expect(request -> {}).andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"error\":{\"status\":\"NOT_FOUND\"}}"));
        var result=client.modelMetadata(); assertFalse(result.success()); assertEquals(404,result.providerHttpStatus());
        assertEquals("GEMINI_MODEL_NOT_FOUND",result.diagnosticCode()); server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"TEXT_JSON","SMALL_IMAGE","COMBINED_SCHEMA"})
    void fixedContractStagesUseNativeJsonAndOnlyTinyImageStageHasAnImage(String name) throws Exception {
        var stage=GeminiFloodVisionClient.ContractStage.valueOf(name);
        server.expect(request -> {
            try {
                var body=mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
                assertEquals("application/json",body.path("generationConfig").path("responseMimeType").asText());
                var parts=body.path("contents").get(0).path("parts");
                assertEquals(stage==GeminiFloodVisionClient.ContractStage.SMALL_IMAGE ? 2 : 1,parts.size());
                if(stage==GeminiFloodVisionClient.ContractStage.COMBINED_SCHEMA)
                    assertEquals(mapper.valueToTree(CombinedAnalysisPrompt.schema()),body.path("generationConfig").path("responseSchema"));
                assertFalse(body.toString().contains("mock-contract-key"));
            } catch(Exception failure) { throw new AssertionError(failure); }
        }).andRespond(withSuccess(envelope(stage==GeminiFloodVisionClient.ContractStage.COMBINED_SCHEMA ?
                "{\"sceneMatch\":false,\"floodWaterVisible\":false,\"approxDepthCm\":null,\"confidence\":\"LOW\",\"reasoning\":\"No photos\"}" : "{\"ok\":true}"),MediaType.APPLICATION_JSON));
        var result=client.diagnoseContract(stage,stage==GeminiFloodVisionClient.ContractStage.SMALL_IMAGE ? SceneTestFixtures.png() : null,
                stage==GeminiFloodVisionClient.ContractStage.SMALL_IMAGE ? "image/png" : null);
        assertTrue(result.success()); assertTrue(result.schemaValid()); server.verify();
    }
    @Test void largeFloodImageAndMimeMismatchNeverReachProvider() {
        assertThrows(IllegalArgumentException.class,()->client.diagnoseContract(GeminiFloodVisionClient.ContractStage.SMALL_IMAGE,new byte[65_537],"image/png"));
        assertThrows(IllegalArgumentException.class,()->client.diagnoseContract(GeminiFloodVisionClient.ContractStage.SMALL_IMAGE,SceneTestFixtures.png(),"image/jpeg"));
        assertThrows(IllegalArgumentException.class,()->client.diagnoseContract(GeminiFloodVisionClient.ContractStage.TEXT_JSON,SceneTestFixtures.png(),"image/png"));
        server.verify();
    }
    @Test void contract400IsNotRetriedAndReturnsOnlyFixedPublicMessage() {
        server.expect(request -> {}).andRespond(withBadRequest().body("{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"Unsupported responseSchema\"}}"));
        var result=client.diagnoseContract(GeminiFloodVisionClient.ContractStage.COMBINED_SCHEMA,null,null);
        assertFalse(result.success()); assertEquals(400,result.providerHttpStatus()); assertEquals("INVALID_ARGUMENT",result.providerCode());
        assertFalse(result.message().contains("Unsupported responseSchema")); server.verify();
    }
}
