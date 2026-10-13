package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.depth.CombinedFixtures;
import com.flooddepth.exception.AiConfigurationException;
import com.flooddepth.exception.AiProviderTimeoutException;
import com.flooddepth.scene.SceneTestFixtures;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiCombinedTimeoutTest {
    final ObjectMapper mapper=new ObjectMapper();
    String envelope(String content) throws Exception {
        return mapper.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",
                Map.of("parts",List.of(Map.of("text",content)))))));
    }
    GeminiFloodVisionClient client(MockWebServer server,int base,int combined) {
        return new GeminiFloodVisionClient(RestClient.builder(),new AiResponseParser(mapper),mapper,"unit-only-key",
                "gemini-3.8-flash",server.url("/v1beta/models/").toString(),base,combined);
    }
    @Test void tenMinuteCombinedReadBudgetDoesNotChangeConnectionOrNormalReadBudgets() {
        var builder=mock(RestClient.Builder.class,RETURNS_SELF);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(RestClient.class));
        new GeminiFloodVisionClient(builder,new AiResponseParser(mapper),mapper,"unit-only-key",
                "gemini-3.8-flash","http://localhost/v1beta/models/",60000,600000);
        var factories=ArgumentCaptor.forClass(ClientHttpRequestFactory.class);
        verify(builder,times(2)).requestFactory(factories.capture());
        var normal=factories.getAllValues().get(0);
        var combined=factories.getAllValues().get(1);
        assertEquals(60000,ReflectionTestUtils.getField(normal,"connectTimeout"));
        assertEquals(60000,ReflectionTestUtils.getField(normal,"readTimeout"));
        assertEquals(60000,ReflectionTestUtils.getField(combined,"connectTimeout"));
        assertEquals(600000,ReflectionTestUtils.getField(combined,"readTimeout"));
    }
    @Test void combinedReadsCanOutlastBaseBudgetWhileOtherOperationsStillUseBaseTimeout() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json")
                    .setBody(envelope(CombinedFixtures.overlapJson())).setBodyDelay(500,TimeUnit.MILLISECONDS));
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json")
                    .setBody(envelope("{\"status\":\"ok\"}")).setBodyDelay(500,TimeUnit.MILLISECONDS));
            var client=client(server,100,2000);
            assertTrue(client.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png").sceneMatch());
            var text=client.diagnose(GeminiFloodVisionClient.DiagnosticStage.TEXT,null,null);
            assertEquals("GEMINI_TIMEOUT",text.diagnosticCode());assertEquals(200,text.providerHttpStatus());
            assertFalse(text.success());assertEquals(2,server.getRequestCount());
        }
    }
    @Test void configurableCombinedBudgetIsFiniteAndTimesOutWithoutRetry() throws Exception {
        try(var server=new MockWebServer()) {
            server.start();server.enqueue(new MockResponse().setHeader("Content-Type","application/json")
                    .setBody(envelope(CombinedFixtures.overlapJson())).setBodyDelay(500,TimeUnit.MILLISECONDS));
            var client=client(server,1000,100);
            assertThrows(AiProviderTimeoutException.class,()->client.analyzeSceneAndDepth(
                    SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png"));
            assertEquals(1,server.getRequestCount());
        }
    }
    @ParameterizedTest @ValueSource(ints={0,-1,600001})
    void unboundedOrExcessiveCombinedConfigurationRejectedBeforeAnyRequest(int timeout) throws Exception {
        try(var server=new MockWebServer()) {
            server.start();assertThrows(AiConfigurationException.class,()->client(server,60000,timeout));
            assertEquals(0,server.getRequestCount());
        }
    }
}
