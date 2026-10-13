package com.flooddepth.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.depth.DepthDiagnostics;
import com.flooddepth.exception.AiProviderTimeoutException;
import com.flooddepth.scene.SceneTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiTransportDiagnosticsTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bodyTimeoutRetainsReceivedStatusAndWhetherAnyResponseBytesArrived(boolean partial) {
        var builder=RestClient.builder(); var server=MockRestServiceServer.bindTo(builder).build();
        String secret="fake-transport-key-only-for-test";
        server.expect(request->{}).andRespond(request->new MockClientHttpResponse(new InputStream() {
            boolean first=true;
            @Override public int read() throws java.io.IOException {
                if(partial && first){first=false;return '{';}
                throw new SocketTimeoutException(secret);
            }
            @Override public int read(byte[] bytes,int offset,int length) throws java.io.IOException {
                int value=read(); bytes[offset]=(byte)value; return 1;
            }
        },HttpStatus.OK));
        var mapper=new ObjectMapper();
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(mapper),secret,"gemini-3.8-flash");
        Logger logger=(Logger)DepthDiagnostics.logger();var logs=new ListAppender<ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            var failure=assertThrows(AiProviderTimeoutException.class,()->client.analyzeSceneAndDepth(
                    SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png"));
            assertEquals(200,failure.providerHttpStatus());assertEquals(partial,failure.responseBodyPresent());assertNull(failure.getCause());
            String output=logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(output.contains("timeoutType=BODY_TIMEOUT")); assertTrue(output.contains("responseStarted=true"));
            assertTrue(output.contains("anyResponseBodyReceived="+partial));
            assertTrue(output.contains("readTimeoutMs=600000"));assertTrue(output.contains("stage=COMBINED_ANALYSIS attempt=1"));
            assertTrue(output.contains("combinedAnalysisTimeoutMs=600000"));
            assertTrue(output.contains("GEMINI_ATTEMPT_START"));assertTrue(output.contains("startedAt="));
            assertTrue(output.contains("timeoutAt="));assertTrue(output.contains("elapsedMs="));
            assertFalse(output.contains(secret));assertFalse(output.contains("inlineData"));assertFalse(output.contains("base64"));
            assertTrue(logs.list.stream().allMatch(l->l.getThrowableProxy()==null));
        } finally {logger.detachAppender(logs);logs.stop();}
        server.verify();
    }
    @Test void canonicalTransportPhasesAreDistinctAndUnidentifiableCausesStayOther() {
        var trace=new GeminiTransportDiagnostics("COMBINED_ANALYSIS",System.nanoTime(),60000,600000);
        assertEquals("CONNECT_TIMEOUT",trace.type(new java.net.http.HttpConnectTimeoutException("private")));
        assertEquals("REQUEST_TIMEOUT",trace.type(new java.net.http.HttpTimeoutException("private")));
        assertEquals("CONNECT_TIMEOUT",trace.type(new SocketTimeoutException("connect timed out")));
        assertEquals("READ_TIMEOUT",trace.type(new SocketTimeoutException("Read timed out")));
        assertEquals("OTHER",trace.type(new SocketTimeoutException("private-unclassified")));
    }
    @Test void aReadTimeoutIsOneAttemptWithoutRetryOrSensitiveCause() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(request->{}).andRespond(withException(new SocketTimeoutException("Read timed out")));
        var client=new GeminiFloodVisionClient(builder.build(),new AiResponseParser(new ObjectMapper()),"mock-key","gemini-3.8-flash");
        var waits=new ArrayList<Long>();
        var failure=assertThrows(com.flooddepth.scene.Gemini503Retry.FailedAttempts.class,()->SceneTestFixtures.retry(waits::add)
                .execute(com.flooddepth.scene.Gemini503Retry.AiStage.COMBINED_ANALYSIS,()->client.analyzeSceneAndDepth(
                        SceneTestFixtures.png(),"image/png",SceneTestFixtures.png(),"image/png")));
        assertEquals(1,failure.attempts().size());assertTrue(waits.isEmpty());assertNull(failure.providerHttpStatus());
        assertEquals(com.flooddepth.exception.GeminiFailureException.Code.GEMINI_TIMEOUT,failure.code());server.verify();
    }
}
