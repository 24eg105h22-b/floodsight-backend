package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class GeminiRateLimitTest {
    final ObjectMapper mapper=new ObjectMapper();
    final Instant now=Instant.parse("2026-10-10T12:00:00Z");
    GeminiRateLimit parse(String details,String header) {
        return GeminiRateLimit.parse(mapper,"{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"private-provider-text\",\"details\":"+details+"}}",header,now);
    }
    @Test void resourceExhaustedAloneDoesNotGuessTemporaryOrHardQuota() {
        var result=parse("[]",null);assertEquals(GeminiRateLimit.Type.UNKNOWN,result.type());assertNull(result.delayMs());
        assertFalse(result.toString().contains("private"));
    }
    @Test void retryAfterSecondsAndHttpDateAreSanitizedToMilliseconds() {
        assertEquals(12000L,parse("[]","12").retryAfterMs());
        assertEquals(15000L,parse("[]","Sat, 10 Oct 2026 12:00:15 GMT").retryAfterMs());
        assertEquals(GeminiRateLimit.Type.UNKNOWN,parse("[]","private-key").type());
        assertEquals(GeminiRateLimit.Type.UNKNOWN,parse("[]","-2").type());
    }
    @Test void protobufRetryDelayRoundsUpAndUsesTheLongerHeaderDelay() {
        var result=parse("[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"3.000000001s\"}]","5");
        assertEquals(3001L,result.retryDelayMs());assertEquals(5000L,result.delayMs());assertTrue(result.retryable());
        assertEquals(GeminiRateLimit.Type.UNKNOWN,parse("[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"3\"}]",null).type());
    }
    @Test void dailyOrZeroQuotaTakesPrecedenceOverRetryAdvice() {
        for(String violation:java.util.List.of("{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel\"}","{\"quotaValue\":\"0\"}","{\"quotaMetric\":\"generate_content_requests_per_day\"}")) {
            var result=parse("[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":["+violation+"]}]","10");
            assertEquals(GeminiRateLimit.Type.HARD_QUOTA,result.type());assertFalse(result.retryable());
        }
    }
    @Test void structuredPerMinuteLimitSupportsBoundedBackoffWithoutHeader() {
        var result=parse("[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"GenerateRequestsPerMinutePerProject\"}]}]",null);
        assertTrue(result.retryable());assertNull(result.delayMs());
    }
    @Test void arbitraryDescriptionsAndMalformedBodiesAreNeverClassifiedOrRetained() {
        assertEquals(GeminiRateLimit.Type.UNKNOWN,parse("[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"description\":\"PerDay quota exhausted private-key\"}]}]",null).type());
        assertEquals(GeminiRateLimit.UNKNOWN,GeminiRateLimit.parse(mapper,"{broken",null,now));
    }
}
