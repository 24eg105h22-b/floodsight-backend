package com.flooddepth.scene;

import com.flooddepth.exception.GeminiFailureException;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Bounded 503 and explicitly retryable 429 transport retry; parsing and policy remain strict. */
@Component
public final class Gemini503Retry {
    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
    public enum AiStage { SCENE_MATCH, DEPTH_ANALYSIS, COMBINED_ANALYSIS }
    public record Attempt(int attempt, Integer providerHttpStatus, long durationMs, String providerCode,
            com.flooddepth.ai.GeminiRateLimit rateLimit) { }
    public record Outcome<T>(T value, List<Attempt> attempts) { }
    public static final class FailedAttempts extends GeminiFailureException {
        private final List<Attempt> attempts;
        FailedAttempts(GeminiFailureException failure, List<Attempt> attempts) {
            super(failure.code(), failure.providerHttpStatus(), failure.providerCode(), failure.durationMs(), failure.responseBodyPresent(),failure.rateLimit());
            this.attempts = List.copyOf(attempts);
        }
        public List<Attempt> attempts() { return attempts; }
    }
    private final Sleeper sleeper;
    private final java.util.function.LongSupplier jitter;
    public Gemini503Retry() { this(Thread::sleep,()->java.util.concurrent.ThreadLocalRandom.current().nextLong(251)); }
    Gemini503Retry(Sleeper sleeper) { this(sleeper,()->0L); }
    Gemini503Retry(Sleeper sleeper,java.util.function.LongSupplier jitter) { this.sleeper = sleeper; this.jitter=jitter; }
    public <T> Outcome<T> execute(Supplier<T> operation) {
        return execute(AiStage.SCENE_MATCH,operation);
    }
    public <T> Outcome<T> execute(AiStage stage,Supplier<T> operation) {
        var attempts = new ArrayList<Attempt>();
        long waited=0;
        for (int attempt = 1; ; attempt++) {
            long start = System.nanoTime();
            String previousAttempt = org.slf4j.MDC.get("geminiAttempt");
            org.slf4j.MDC.put("geminiAttempt", Integer.toString(attempt));
            try {
                T result = operation.get();
                attempts.add(new Attempt(attempt, 200, (System.nanoTime() - start) / 1_000_000, null,com.flooddepth.ai.GeminiRateLimit.UNKNOWN));
                return new Outcome<>(result, List.copyOf(attempts));
            } catch (GeminiFailureException failure) {
                attempts.add(new Attempt(attempt, failure.providerHttpStatus(), failure.durationMs(), failure.providerCode(),failure.rateLimit()));
                boolean retry429=failure.isRateLimit() && Integer.valueOf(429).equals(failure.providerHttpStatus()) && failure.rateLimit().retryable();
                if(Integer.valueOf(429).equals(failure.providerHttpStatus()))
                    com.flooddepth.depth.DepthDiagnostics.logger().info("AI_STAGE={} HTTP_STATUS=429 RATE_LIMIT_RETRYABLE={} RATE_LIMIT_TYPE={} RETRY_AFTER_MS={} RETRY_DELAY_MS={} PROVIDER_CODE={} ATTEMPT={}",
                            stage,retry429,failure.rateLimit().type(),failure.rateLimit().retryAfterMs(),failure.rateLimit().retryDelayMs(),failure.providerCode(),attempt);
                boolean retry503=failure.code()==GeminiFailureException.Code.GEMINI_PROVIDER_5XX && Integer.valueOf(503).equals(failure.providerHttpStatus());
                if(attempt>=3 || !(retry429 || retry503))throw new FailedAttempts(failure,attempts);
                long delay=2000L << (attempt-1);
                if(retry429) {
                    Long recommended=failure.rateLimit().delayMs();
                    delay=recommended==null?delay:Math.max(delay,recommended);
                    if(delay>60000-waited)throw new FailedAttempts(failure,attempts); // Never retry before a longer provider delay.
                    delay+=Math.min(250,Math.max(0,jitter.getAsLong()));
                }
                if(delay>60000-waited)throw new FailedAttempts(failure,attempts);
                waited+=delay;
                try { sleeper.sleep(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FailedAttempts(failure, attempts);
                }
            } finally {
                if (previousAttempt == null) org.slf4j.MDC.remove("geminiAttempt");
                else org.slf4j.MDC.put("geminiAttempt", previousAttempt);
            }
        }
    }
}
