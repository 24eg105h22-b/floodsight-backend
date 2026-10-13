package com.flooddepth.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.exception.GeminiFailureException;
import java.util.ArrayList;
import java.util.List;

/** Only bounded, redacted scalar error details. Never returns a raw body, header or request payload. */
final class GeminiProviderErrorDiagnostics {
    private GeminiProviderErrorDiagnostics() { }
    record FieldViolation(String field, String description) { }
    record Details(String providerStatus, Integer providerErrorCode, String message, List<FieldViolation> fieldViolations) { }

    static Details parse(ObjectMapper mapper, String body, String credential) {
        if (body == null || body.length() > 128_000) return empty();
        try {
            var error = mapper.readTree(body).path("error");
            if (!error.isObject()) return empty();
            var status = new GeminiFailureException(GeminiFailureException.Code.GEMINI_PROVIDER_4XX,
                    null, error.path("status").asText(), 0, false).providerCode();
            Integer code = error.path("code").isIntegralNumber() && error.path("code").intValue() >= 100
                    && error.path("code").intValue() <= 599 ? error.path("code").intValue() : null;
            var fields = new ArrayList<FieldViolation>();
            var details = error.path("details");
            if (details.isArray()) for (var detail : details) {
                if (!"type.googleapis.com/google.rpc.BadRequest".equals(detail.path("@type").asText())) continue;
                if (!detail.path("fieldViolations").isArray()) continue;
                for (var violation : detail.path("fieldViolations")) {
                    if (fields.size() == 8) break;
                    String field = violation.path("field").asText("");
                    if (!field.matches("[A-Za-z0-9_.\\[\\]-]{1,300}")) field = "[REDACTED_FIELD]";
                    fields.add(new FieldViolation(sanitize(field, credential), sanitize(violation.path("description").asText(""), credential)));
                }
            }
            return new Details(status, code, sanitize(error.path("message").asText(""), credential), List.copyOf(fields));
        } catch (Exception ignored) { return empty(); }
    }
    private static Details empty() { return new Details(null, null, "Provider error details unavailable.", List.of()); }

    static String sanitize(String value, String credential) {
        if (value == null || value.isBlank()) return "No provider message supplied.";
        // Payload/header echoes are omitted wholesale, even when a small image is echoed.
        if (value.matches("(?is).*(?:inlineData\\s*[:=]\\s*\\{|inline_data\\s*[:=]\\s*\\{|\\\"contents\\\"\\s*:|\\\"data\\\"\\s*:|authorization|x-goog-api-key).*"))
            return "Provider detail omitted because it contains request data or credential headers.";
        String safe = credential == null || credential.isEmpty() ? value : value.replace(credential, "[REDACTED]");
        safe = safe.replaceAll("(?i)Bearer\\s+[^\\s,;\\\"']+", "Bearer [REDACTED]")
                .replaceAll("(?i)((?:api[_ -]?key|access[_ -]?token|token|key)\\s*[:=]\\s*)[^\\s,;\\\"']+", "$1[REDACTED]")
                .replaceAll("https?://[^\\s\\\"']+", "[REDACTED_URL]")
                .replaceAll("[A-Za-z0-9_+/=-]{32,}", "[REDACTED_TOKEN]")
                .replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
        return safe.length() > 2000 ? safe.substring(0, 2000) + " [TRUNCATED]" : safe;
    }
}
