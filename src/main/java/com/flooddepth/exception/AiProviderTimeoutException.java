package com.flooddepth.exception;

public class AiProviderTimeoutException extends GeminiFailureException {
    public AiProviderTimeoutException() { this(0); }
    public AiProviderTimeoutException(long durationMs) { super(Code.GEMINI_TIMEOUT, null, null, durationMs, false); }
    public AiProviderTimeoutException(long durationMs, Integer status, boolean bodyPresent) {
        super(Code.GEMINI_TIMEOUT, status, null, durationMs, bodyPresent);
    }
}
