package com.flooddepth.exception;

/** Only fixed diagnostic strings and scalar metadata; never retains upstream bodies or causes. */
public class GeminiFailureException extends AiProviderResponseException {
    public enum Code {
        GEMINI_AUTH_FAILURE("Gemini rejected API authentication or permissions."),
        GEMINI_MODEL_NOT_FOUND("Gemini model or endpoint was not found."),
        GEMINI_RATE_LIMIT("Gemini quota or rate limit was reached."),
        GEMINI_RATE_LIMIT_RETRYABLE("Gemini temporarily rate limited the request."),
        GEMINI_QUOTA_EXHAUSTED("Gemini reported exhausted quota."),
        GEMINI_PROVIDER_4XX("Gemini rejected the request."),
        GEMINI_PROVIDER_5XX("Gemini provider encountered a server error."),
        GEMINI_TIMEOUT("Gemini request timed out."),
        GEMINI_NETWORK_ERROR("Gemini could not be reached."),
        GEMINI_MALFORMED_RESPONSE("Gemini returned malformed or incomplete JSON content."),
        GEMINI_RESPONSE_VALIDATION_FAILED("Gemini response failed strict schema or safety validation.");

        private final String message;
        Code(String message) { this.message = message; }
        public String safeMessage() { return message; }
    }

    private final Code code;
    private final Integer providerHttpStatus;
    private final String providerCode;
    private final long durationMs;
    private final boolean responseBodyPresent;
    private final com.flooddepth.ai.GeminiRateLimit rateLimit;

    public GeminiFailureException(Code code) { this(code, null, null, 0, false); }

    public GeminiFailureException(Code code, Integer status, String providerCode, long durationMs, boolean bodyPresent) {
        this(code,status,providerCode,durationMs,bodyPresent,com.flooddepth.ai.GeminiRateLimit.UNKNOWN);
    }

    public GeminiFailureException(Code code, Integer status, String providerCode, long durationMs, boolean bodyPresent,
            com.flooddepth.ai.GeminiRateLimit rateLimit) {
        super(code.safeMessage() + (status == null ? "" : " Provider HTTP " + status + "."));
        this.code = code;
        this.providerHttpStatus = status;
        // Only Google RPC names are retained, never arbitrary provider strings.
        this.providerCode = providerCode != null && java.util.Set.of("INVALID_ARGUMENT", "UNAUTHENTICATED",
                "PERMISSION_DENIED", "NOT_FOUND", "RESOURCE_EXHAUSTED", "INTERNAL", "UNAVAILABLE",
                "DEADLINE_EXCEEDED", "FAILED_PRECONDITION", "API_KEY_INVALID", "API_KEY_EXPIRED",
                "API_KEY_SERVICE_BLOCKED", "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED")
                .contains(providerCode) ? providerCode : null;
        this.durationMs = Math.max(0, durationMs);
        this.responseBodyPresent = bodyPresent;
        this.rateLimit = rateLimit == null ? com.flooddepth.ai.GeminiRateLimit.UNKNOWN : rateLimit;
    }

    public Code code() { return code; }
    public Integer providerHttpStatus() { return providerHttpStatus; }
    public String providerCode() { return providerCode; }
    public long durationMs() { return durationMs; }
    public boolean responseBodyPresent() { return responseBodyPresent; }
    public com.flooddepth.ai.GeminiRateLimit rateLimit() { return rateLimit; }
    public boolean isRateLimit() { return code == Code.GEMINI_RATE_LIMIT || code == Code.GEMINI_RATE_LIMIT_RETRYABLE || code == Code.GEMINI_QUOTA_EXHAUSTED; }
}
