package com.flooddepth.scene;

import com.flooddepth.exception.GeminiFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class Gemini503RetryTest {
    @Test void provider503RetriesAtMostThreeWithExponentialBackoffAndRetainsAttempts() {
        var delays=new ArrayList<Long>(); var policy=new Gemini503Retry(delays::add); var calls=new AtomicInteger();
        var result=policy.execute(()->{if(calls.incrementAndGet()<3)throw unavailable(); return "ok";});
        assertEquals("ok",result.value()); assertEquals(java.util.List.of(2000L,4000L),delays);
        assertEquals(java.util.List.of(503,503,200),result.attempts().stream().map(Gemini503Retry.Attempt::providerHttpStatus).toList());
        calls.set(0);delays.clear();
        var failure=assertThrows(Gemini503Retry.FailedAttempts.class,()->policy.execute(()->{calls.incrementAndGet();throw unavailable();}));
        assertEquals(3,calls.get()); assertEquals(3,failure.attempts().size()); assertNull(failure.getCause());
    }
    @ParameterizedTest
    @CsvSource({"GEMINI_MALFORMED_RESPONSE,200","GEMINI_RESPONSE_VALIDATION_FAILED,200",
        "GEMINI_AUTH_FAILURE,403","GEMINI_PROVIDER_4XX,400","GEMINI_RATE_LIMIT,429",
        "GEMINI_PROVIDER_5XX,500","GEMINI_TIMEOUT,503","GEMINI_NETWORK_ERROR,503"})
    void only503ProviderFailuresAreRetried(GeminiFailureException.Code code,int status) {
        var calls=new AtomicInteger(); var delays=new ArrayList<Long>();
        var failure=assertThrows(Gemini503Retry.FailedAttempts.class,()->new Gemini503Retry(delays::add).execute(()->{
            calls.incrementAndGet();throw new GeminiFailureException(code,status,null,10,true);
        }));
        assertEquals(1,calls.get()); assertTrue(delays.isEmpty()); assertEquals(code,failure.code());
    }
    @Test void retryable429RespectsProviderDelayAndEventuallySucceeds() {
        var delays=new ArrayList<Long>();var calls=new AtomicInteger();
        var result=new Gemini503Retry(delays::add).execute(()->{if(calls.incrementAndGet()<3)throw limited(com.flooddepth.ai.GeminiRateLimit.Type.RETRYABLE,7000L);return "ok";});
        assertEquals("ok",result.value());assertEquals(java.util.List.of(7000L,7000L),delays);
        assertEquals(java.util.List.of(429,429,200),result.attempts().stream().map(Gemini503Retry.Attempt::providerHttpStatus).toList());
    }
    @Test void repeated429StopsAtThreeWithBackoffWhenNoDelayProvided() {
        var delays=new ArrayList<Long>();var calls=new AtomicInteger();
        var failure=assertThrows(Gemini503Retry.FailedAttempts.class,()->new Gemini503Retry(delays::add).execute(()->{calls.incrementAndGet();throw limited(com.flooddepth.ai.GeminiRateLimit.Type.RETRYABLE,null);}));
        assertEquals(3,calls.get());assertEquals(java.util.List.of(2000L,4000L),delays);assertTrue(failure.rateLimit().retryable());
    }
    @Test void hardUnknownAndDelayAboveWaitBudgetAreNotRetriedEarly() {
        for(var rate:java.util.List.of(new com.flooddepth.ai.GeminiRateLimit(com.flooddepth.ai.GeminiRateLimit.Type.HARD_QUOTA,1000L,null),
                com.flooddepth.ai.GeminiRateLimit.UNKNOWN,new com.flooddepth.ai.GeminiRateLimit(com.flooddepth.ai.GeminiRateLimit.Type.RETRYABLE,61000L,null))) {
            var calls=new AtomicInteger();var delays=new ArrayList<Long>();
            assertThrows(Gemini503Retry.FailedAttempts.class,()->new Gemini503Retry(delays::add).execute(()->{calls.incrementAndGet();throw new GeminiFailureException(GeminiFailureException.Code.GEMINI_RATE_LIMIT,429,"RESOURCE_EXHAUSTED",10,true,rate);}));
            assertEquals(1,calls.get());assertTrue(delays.isEmpty());
        }
    }
    @Test void safeRateDiagnosticsIdentifyDepthStageAndNeverRetainSensitiveText() {
        var logger=(ch.qos.logback.classic.Logger)com.flooddepth.depth.DepthDiagnostics.logger();
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();logs.start();logger.addAppender(logs);
        try {
            assertThrows(Gemini503Retry.FailedAttempts.class,()->new Gemini503Retry(millis->{}).execute(Gemini503Retry.AiStage.DEPTH_ANALYSIS,()->{throw limited(com.flooddepth.ai.GeminiRateLimit.Type.HARD_QUOTA,1000L);}));
            String output=logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).reduce("",String::concat);
            assertTrue(output.contains("AI_STAGE=DEPTH_ANALYSIS HTTP_STATUS=429 RATE_LIMIT_RETRYABLE=false"));
            assertTrue(output.contains("RETRY_AFTER_MS=1000"));assertTrue(output.contains("PROVIDER_CODE=RESOURCE_EXHAUSTED"));
            assertTrue(logs.list.stream().allMatch(l->l.getThrowableProxy()==null));
        } finally {logger.detachAppender(logs);logs.stop();}
    }
    private static GeminiFailureException limited(com.flooddepth.ai.GeminiRateLimit.Type type,Long delay) {
        return new GeminiFailureException(GeminiFailureException.Code.GEMINI_RATE_LIMIT_RETRYABLE,429,"RESOURCE_EXHAUSTED",10,true,new com.flooddepth.ai.GeminiRateLimit(type,delay,null));
    }
    private static GeminiFailureException unavailable(){return new GeminiFailureException(GeminiFailureException.Code.GEMINI_PROVIDER_5XX,503,"UNAVAILABLE",10,true);}
    @Test void attemptDiagnosticContextIsScopedAndRestoredWithoutChangingRetryBudget() {
        org.slf4j.MDC.put("geminiAttempt","outer");
        var observed=new ArrayList<String>();
        try {
            new Gemini503Retry(millis->{}).execute(()->{
                String attempt=org.slf4j.MDC.get("geminiAttempt");observed.add(attempt);
                if(!"3".equals(attempt))throw unavailable();return "ok";
            });
            assertEquals(java.util.List.of("1","2","3"),observed);
            assertEquals("outer",org.slf4j.MDC.get("geminiAttempt"));
        } finally {org.slf4j.MDC.remove("geminiAttempt");}
    }
}
