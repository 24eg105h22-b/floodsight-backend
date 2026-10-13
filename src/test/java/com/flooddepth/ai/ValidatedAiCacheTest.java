package com.flooddepth.ai;

import com.flooddepth.depth.CombinedAnalysis;
import com.flooddepth.depth.CombinedFixtures;
import com.flooddepth.scene.SceneTestFixtures;
import com.flooddepth.exception.GeminiFailureException;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ValidatedAiCacheTest {
    @Test void combinedCacheNeverRetainsProviderOrValidationFailures() {
        var cache=new ValidatedAiCache(); var calls=new AtomicInteger();
        var key=ValidatedAiCache.key("COMBINED_ANALYSIS","flood","1",new byte[]{3},"schema-v1","model",
                new byte[]{1},new byte[]{2},"image/png","image/png");
        for(var code:java.util.List.of(GeminiFailureException.Code.GEMINI_QUOTA_EXHAUSTED,
                GeminiFailureException.Code.GEMINI_RATE_LIMIT_RETRYABLE, GeminiFailureException.Code.GEMINI_PROVIDER_5XX,
                GeminiFailureException.Code.GEMINI_MALFORMED_RESPONSE, GeminiFailureException.Code.GEMINI_RESPONSE_VALIDATION_FAILED))
            for(int i=0;i<2;i++) assertThrows(GeminiFailureException.class,()->cache.getOrCompute(key,
                    com.flooddepth.depth.CombinedAnalysis.class,()->{calls.incrementAndGet();throw new GeminiFailureException(code);}));
        assertEquals(10,calls.get());
        var success=cache.getOrCompute(key,com.flooddepth.depth.CombinedAnalysis.class,com.flooddepth.depth.CombinedFixtures::overlapAssessment);
        assertSame(success,cache.getOrCompute(key,com.flooddepth.depth.CombinedAnalysis.class,()->{fail("Cached combined response must be reused");return null;}));
    }
    ValidatedAiCache.Key key(String image,String id,String version) {
        return ValidatedAiCache.key("COMBINED_ANALYSIS",ValidatedAiCache.hash(image.getBytes()),id,new byte[]{3},version,"model",new byte[]{1},new byte[]{2},"image/png","image/png");
    }
    @Test void identicalInputsReuseValidatedSuccessButDifferentImageReferenceAndPromptDoNot() {
        var cache=new ValidatedAiCache();var calls=new AtomicInteger();
        var operation=(java.util.function.Supplier<CombinedAnalysis>)()->{calls.incrementAndGet();return CombinedFixtures.overlapAssessment();};
        cache.getOrCompute(key("photo-a","1","v1"),CombinedAnalysis.class,operation);
        cache.getOrCompute(key("photo-a","1","v1"),CombinedAnalysis.class,operation);assertEquals(1,calls.get());
        for(var k:java.util.List.of(key("photo-b","1","v1"),key("photo-a","2","v1"),key("photo-a","1","v2")))cache.getOrCompute(k,CombinedAnalysis.class,operation);
        assertEquals(4,calls.get());
    }
    @Test void ttlExpiresAndLeastRecentlyUsedEntryIsEvictedAtBound() {
        var clock=new AtomicLong();var cache=new ValidatedAiCache(2,10,clock::get);var calls=new AtomicInteger();
        var operation=(java.util.function.Supplier<CombinedAnalysis>)()->{calls.incrementAndGet();return CombinedFixtures.overlapAssessment();};
        for(String id:java.util.List.of("1","2","1","3","2"))cache.getOrCompute(key("a",id,"v1"),CombinedAnalysis.class,operation);
        assertEquals(4,calls.get());clock.set(10);cache.getOrCompute(key("a","2","v1"),CombinedAnalysis.class,operation);assertEquals(5,calls.get());
    }
    @Test void rateProviderAndParserFailuresAreNeverCached() {
        var cache=new ValidatedAiCache();var calls=new AtomicInteger();
        for(var code:java.util.List.of(GeminiFailureException.Code.GEMINI_RATE_LIMIT,GeminiFailureException.Code.GEMINI_PROVIDER_5XX,GeminiFailureException.Code.GEMINI_MALFORMED_RESPONSE)) {
            for(int i=0;i<2;i++)assertThrows(GeminiFailureException.class,()->cache.getOrCompute(key("a","1","v1"),CombinedAnalysis.class,()->{calls.incrementAndGet();throw new GeminiFailureException(code);}));
        }
        assertEquals(6,calls.get());
        assertEquals(CombinedFixtures.overlapAssessment(),cache.getOrCompute(key("a","1","v1"),CombinedAnalysis.class,CombinedFixtures::overlapAssessment));
    }
    @Test void concurrentIdenticalInputsShareOneInFlightCall() throws Exception {
        var cache=new ValidatedAiCache();var calls=new AtomicInteger();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->cache.getOrCompute(key("a","1","v1"),CombinedAnalysis.class,()->{
                calls.incrementAndGet();entered.countDown();try{assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException();}return CombinedFixtures.overlapAssessment();
            }));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var second=pool.submit(()->cache.getOrCompute(key("a","1","v1"),CombinedAnalysis.class,()->{calls.incrementAndGet();return CombinedFixtures.overlapAssessment();}));
            release.countDown();assertEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,calls.get());
        } finally {release.countDown();pool.shutdownNow();}
    }
}
