package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.scene.SceneTestFixtures;
import com.flooddepth.depth.DepthDiagnostics;
import com.flooddepth.exception.GeminiFailureException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiProviderErrorDiagnosticsTest {
    final ObjectMapper mapper=new ObjectMapper();
    final String key="test-diagnostic-private-key";
    @Test void logs400StatusModelStageAndFieldDetailsWithoutCredentialsOrPayloads() throws Exception {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        var violation=Map.of("field","generation_config.response_schema","description","Maximum nesting exceeded; key="+key);
        var detail=Map.of("@type","type.googleapis.com/google.rpc.BadRequest","fieldViolations",List.of(violation));
        var error=Map.of("code",400,"status","INVALID_ARGUMENT","message","responseSchema is too complex. "+key+"\nsecond line",
                "details",List.of(detail));
        var body=mapper.writeValueAsString(Map.of("error",error));
        server.expect(request->{}).andRespond(withBadRequest().body(body));
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),key,"gemini-3.8-flash");
        Logger logger=(Logger)LoggerFactory.getLogger(DepthDiagnostics.class);
        var logs=new ListAppender<ILoggingEvent>(); logs.start();logger.addAppender(logs);MDC.put("depthStage","COMBINED_ANALYSIS");
        try {
            var failure=assertThrows(GeminiFailureException.class,()->client.analyzeSceneAndDepth(SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png"));
            var output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertTrue(output.contains("GEMINI_PROVIDER_ERROR")); assertTrue(output.contains("stage=COMBINED_ANALYSIS"));
            assertTrue(output.contains("model=gemini-3.8-flash"));assertTrue(output.contains("providerStatus=INVALID_ARGUMENT"));
            assertTrue(output.contains("responseSchema is too complex"));assertTrue(output.contains("generation_config.response_schema"));
            assertFalse(output.contains(key));assertFalse(output.contains("\nsecond line"));
            assertNull(failure.getCause());assertFalse(failure.getMessage().contains("responseSchema is too complex"));
            server.verify();
        } finally {logger.detachAppender(logs);logs.stop();MDC.remove("depthStage");}
    }
    @Test void omitsEchoedImagePayloadAndCredentialHeadersWholesale() throws Exception {
        for(String text:List.of("Authorization: Bearer OTHER_SECRET", "x-goog-api-key: OTHER_SECRET",
                "inlineData={data:abc}","request {\"contents\":[{\"data\":\"YWI=\"}]}")) {
            String body=mapper.writeValueAsString(Map.of("error",Map.of("message",text)));
            var safe=GeminiProviderErrorDiagnostics.parse(mapper,body,key);
            assertFalse(safe.message().contains("OTHER_SECRET"));assertFalse(safe.message().contains("YWI="));
            assertTrue(safe.message().contains("omitted"));
        }
    }
    @Test void retainsInvalidArgumentFieldPathsWithoutImageData() {
        String message="Invalid value at contents[0].parts[1].inline_data.mime_type: unsupported MIME type";
        assertEquals(message,GeminiProviderErrorDiagnostics.sanitize(message,key));
    }
    @Test void boundsMalformedBodiesAndRedactsUrlsTokensAndKnownCredential() throws Exception {
        assertTrue(GeminiProviderErrorDiagnostics.parse(mapper,"not json",key).message().contains("unavailable"));
        assertTrue(GeminiProviderErrorDiagnostics.parse(mapper,"a".repeat(128001),key).message().contains("unavailable"));
        var secret="unknownBase64EncodedCredentialOrImage0123456789=";
        var result=GeminiProviderErrorDiagnostics.sanitize("API rejected "+key+" "+secret+" https://example.invalid/?key=other-private-value",key);
        assertFalse(result.contains(key));assertFalse(result.contains(secret));assertFalse(result.contains("other-private-value"));
        assertTrue(GeminiProviderErrorDiagnostics.sanitize("a ".repeat(4000),key).length()<2100);
    }
}
