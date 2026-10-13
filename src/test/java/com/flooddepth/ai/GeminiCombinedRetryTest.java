package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.ai.*;
import com.flooddepth.scene.*;
import com.flooddepth.depth.CombinedFixtures;
import com.flooddepth.exception.GeminiFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiCombinedRetryTest {
    final ObjectMapper mapper=new ObjectMapper();
    String envelope(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",text)))))));
    }
    GeminiFloodVisionClient client(RestClient.Builder builder) { return new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),"mock-depth-key","gemini-3.8-flash"); }
    @Test void retriesOnly503AndParsesTheSuccessfulTwoImageResponse() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        for(int i=0;i<2;i++) server.expect(request->{}).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"error\":{\"status\":\"UNAVAILABLE\"}}"));
        server.expect(request->{}).andRespond(withSuccess(envelope(CombinedFixtures.overlapJson()),MediaType.APPLICATION_JSON));
        var waits=new ArrayList<Long>(); var retry=SceneTestFixtures.retry(waits::add); var gemini=client(builder);
        var result=retry.execute(Gemini503Retry.AiStage.COMBINED_ANALYSIS,()->gemini.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png"));
        assertTrue(result.value().sceneMatch()); assertEquals(List.of(503,503,200),result.attempts().stream().map(Gemini503Retry.Attempt::providerHttpStatus).toList());
        assertEquals(List.of(2000L,4000L),waits); server.verify();
    }
    @Test void exhausted503HasExactlyThreeAttemptsAndNoUnboundedLoop() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        for(int i=0;i<3;i++) server.expect(request->{}).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"error\":{\"status\":\"UNAVAILABLE\"}}"));
        var waits=new ArrayList<Long>(); var retry=SceneTestFixtures.retry(waits::add); var gemini=client(builder);
        var failure=assertThrows(Gemini503Retry.FailedAttempts.class,()->retry.execute(Gemini503Retry.AiStage.COMBINED_ANALYSIS,()->gemini.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png")));
        assertEquals(3,failure.attempts().size()); assertEquals(503,failure.providerHttpStatus()); assertEquals(2,waits.size()); server.verify();
    }
    @Test void successfulHttpWithMalformedJsonIsNeverRetried() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request->{}).andRespond(withSuccess(envelope("```json\n{}\n```"),MediaType.APPLICATION_JSON));
        var waits=new ArrayList<Long>(); var retry=SceneTestFixtures.retry(waits::add); var gemini=client(builder);
        var failure=assertThrows(Gemini503Retry.FailedAttempts.class,()->retry.execute(Gemini503Retry.AiStage.COMBINED_ANALYSIS,()->gemini.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png")));
        assertEquals(GeminiFailureException.Code.GEMINI_MALFORMED_RESPONSE,failure.code()); assertEquals(200,failure.providerHttpStatus());
        assertEquals(1,failure.attempts().size()); assertTrue(waits.isEmpty()); server.verify();
    }
    @Test void retryable429PreservesProviderDelayAndRetriesCombinedRequestOnly() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request->{}).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","7")
                .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"GenerateRequestsPerMinute\"}]}]}}"));
        server.expect(request->{}).andRespond(withSuccess(envelope(CombinedFixtures.overlapJson()),MediaType.APPLICATION_JSON));
        var waits=new ArrayList<Long>(); var retry=SceneTestFixtures.retry(waits::add); var gemini=client(builder);
        var result=retry.execute(Gemini503Retry.AiStage.COMBINED_ANALYSIS,()->gemini.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png"));
        assertEquals(List.of(429,200),result.attempts().stream().map(Gemini503Retry.Attempt::providerHttpStatus).toList());
        assertEquals(List.of(7000L),waits); assertTrue(result.attempts().get(0).rateLimit().retryable()); server.verify();
    }
    @Test void hardQuotaIsOneAttemptWithoutRetryAndMetadataSurvivesCombinedTransport() {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request->{}).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","100")
                .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"GenerateRequestsPerDay\"}]}]}}"));
        var waits=new ArrayList<Long>(); var retry=SceneTestFixtures.retry(waits::add); var gemini=client(builder);
        var failure=assertThrows(Gemini503Retry.FailedAttempts.class,()->retry.execute(Gemini503Retry.AiStage.COMBINED_ANALYSIS,
                ()->gemini.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png")));
        assertEquals(1,failure.attempts().size()); assertEquals(GeminiFailureException.Code.GEMINI_QUOTA_EXHAUSTED,failure.code());
        assertEquals(100000L,failure.rateLimit().retryAfterMs()); assertTrue(waits.isEmpty()); server.verify();
    }
}
