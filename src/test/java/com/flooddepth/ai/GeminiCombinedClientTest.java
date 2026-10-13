package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.depth.CombinedFixtures;
import com.flooddepth.scene.SceneTestFixtures;
import com.flooddepth.exception.GeminiFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiCombinedClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void emptyEitherImageNeverContactsProvider() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),"mock-depth-key","gemini-3.8-flash");
        for(byte[] invalid:new byte[][]{null,new byte[0]}) {
            assertThrows(IllegalArgumentException.class,()->client.analyzeSceneAndDepth(invalid,"image/png",SceneTestFixtures.png(),"image/png"));
            assertThrows(IllegalArgumentException.class,()->client.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",invalid,"image/png"));
        } server.verify();
    }
    String envelope(String text) throws Exception {
        var content = Map.of("parts", List.of(Map.of("text", text)));
        return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content", content))));
    }
    @Test void twoImagesNativeSchemaStrictParserAndHeaderOnlyCredential() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        byte[] flood=SceneTestFixtures.png();
        var dryImage=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);
        dryImage.setRGB(10,10,0xFF0000); var output=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(dryImage,"png",output);
        byte[] dry=output.toByteArray(); assertFalse(java.util.Arrays.equals(flood,dry));
        final byte[] reference=dry;
        server.expect(request->{
            assertNull(request.getURI().getQuery()); assertEquals("mock-depth-key",request.getHeaders().getFirst("x-goog-api-key"));
            try {
                var body=mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
                assertFalse(body.toString().contains("mock-depth-key"));
                var generation=body.path("generationConfig"); assertEquals("application/json",generation.path("responseMimeType").asText());
                var fields=generation.path("responseSchema").path("properties");
                assertEquals(5,fields.size()); assertTrue(fields.has("sceneMatch"));assertTrue(fields.has("approxDepthCm"));
                assertFalse(fields.has("referenceCandidates"));assertFalse(fields.has("sceneCalibration"));
                var parts=body.path("contents").get(0).path("parts");
                assertArrayEquals(flood,Base64.getDecoder().decode(parts.get(2).path("inlineData").path("data").asText()));
                assertArrayEquals(reference,Base64.getDecoder().decode(parts.get(4).path("inlineData").path("data").asText()));
                String sentPrompt=parts.get(0).path("text").asText().replaceAll("\\s+"," ");
                assertTrue(sentPrompt.contains("approxDepthCm"));
                assertTrue(sentPrompt.contains("Image 3, when available"));
                assertTrue(sentPrompt.contains("ORIGINAL two images"));
                assertTrue(sentPrompt.contains("Horizontal flood coverage indicates extent, not depth"));
                assertTrue(sentPrompt.contains("where would water reach on them?"));
                assertTrue(sentPrompt.contains("no metric calibration"));
                assertEquals(2, java.util.stream.StreamSupport.stream(parts.spliterator(), false).filter(part -> part.has("inlineData")).count());
            } catch(Exception e) { throw new AssertionError(e); }
        }).andRespond(withSuccess(envelope(structuredResponse()),MediaType.APPLICATION_JSON));
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),"mock-depth-key","gemini-3.8-flash");
        var parsed=client.analyzeSceneAndDepth(flood,"image/png",reference,"image/png");
        assertTrue(parsed.sceneMatch());assertEquals(28.4,parsed.visualDepthEstimate().approxDepthCm());
    }
    String structuredResponse() { return CombinedFixtures.overlapJson(); }
    @Test void optionalThirdOverlayUsesTheSameSingleNativeJsonRequestAndKeepsOriginalBytes() throws Exception {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        byte[] flood=SceneTestFixtures.png(),dry=SceneTestFixtures.png();
        var picture=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);
        picture.setRGB(5,5,0xFF0000);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(picture,"png",out);
        byte[] overlay=out.toByteArray();
        server.expect(request->{
            try {
                var body=mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString());
                var parts=body.path("contents").get(0).path("parts");
                assertEquals(7,parts.size());
                String prompt=parts.get(0).path("text").asText().replaceAll("\\s+"," ");
                assertTrue(prompt.contains("registered overlay"));
                assertTrue(prompt.contains("where would water reach on them?"));
                assertTrue(prompt.contains("Vehicles may be dry-only scale references"));
                assertTrue(prompt.contains("lower vehicle bodies"));
                assertArrayEquals(flood,Base64.getDecoder().decode(parts.get(2).path("inlineData").path("data").asText()));
                assertArrayEquals(dry,Base64.getDecoder().decode(parts.get(4).path("inlineData").path("data").asText()));
                assertArrayEquals(overlay,Base64.getDecoder().decode(parts.get(6).path("inlineData").path("data").asText()));
                assertEquals("image/png",parts.get(6).path("inlineData").path("mimeType").asText());
                assertTrue(parts.get(5).path("text").asText().contains("NOT metric evidence or proof of scene identity"));
                assertEquals("application/json",body.path("generationConfig").path("responseMimeType").asText());
                assertEquals(mapper.valueToTree(com.flooddepth.depth.CombinedAnalysisPrompt.schema()),body.path("generationConfig").path("responseSchema"));
            }catch(Exception e){throw new AssertionError(e);}
        }).andRespond(withSuccess(envelope(structuredResponse()),MediaType.APPLICATION_JSON));
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),"mock-depth-key","gemini-3.8-flash");
        try(var log=new com.flooddepth.depth.DepthDiagnosticCapture()) {
            assertTrue(client.analyzeSceneAndDepth(flood,"image/png",dry,"image/png",overlay,"image/png").visualDepthEstimate().possible());
            assertTrue(log.messages().contains("overlaySentToGemini=true"));
            assertFalse(log.messages().contains(Base64.getEncoder().encodeToString(overlay)));
        }
        server.verify();
    }
    @Test void invalidOptionalOverlayNeverContactsProvider() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),"mock-depth-key","gemini-3.8-flash");
        assertThrows(IllegalArgumentException.class,()->client.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png",new byte[0],"image/png"));
        assertThrows(IllegalArgumentException.class,()->client.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/jpeg"));
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"prose", "```json\n{}\n```", "{\"depthCm\":16}", "mock-depth-key"})
    void unsafeResponsesRejectedWithoutSecretBearingCause(String text) throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request->{}).andRespond(withSuccess(envelope(text),MediaType.APPLICATION_JSON));
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),"mock-depth-key","gemini-3.8-flash");
        var failure=assertThrows(GeminiFailureException.class,()->client.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png"));
        assertFalse(failure.getMessage().contains("mock-depth-key")); assertNull(failure.getCause()); server.verify();
    }
}
