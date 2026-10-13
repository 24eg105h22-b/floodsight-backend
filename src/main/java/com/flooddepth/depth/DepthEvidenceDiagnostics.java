package com.flooddepth.depth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allowlisted, sanitized parsed evidence only. No provider/client, payload or credential access. */
final class DepthEvidenceDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();
    static void overlap(CombinedAnalysis combined, VisualDepthEstimator.Calculation result) {
        var visual = combined.visualDepthEstimate();
        var fields = new LinkedHashMap<String, Object>();
        fields.put("SCENE_MATCH", combined.sceneMatch());
        fields.put("SCENE_DECISION", combined.sceneMatch() ? "LIKELY_SAME_SCENE" : "LIKELY_DIFFERENT_SCENE");
        fields.put("SCENE_CONFIDENCE", null); // Not supplied by the minimal contract.
        fields.put("FLOOD_WATER_VISIBLE", combined.floodWaterVisible());
        fields.put("VISUAL_ESTIMATE_POSSIBLE", visual.possible());
        fields.put("VISUAL_APPROX_DEPTH_CM", visual.possible() ? visual.approxDepthCm() : null);
        fields.put("VISUAL_CONFIDENCE", visual.confidence());
        fields.put("VISUAL_REASONING", text(visual.reasoning()));
        fields.put("JAVA_ACCEPTED", result.estimated());
        fields.put("FINAL_APPROX_DEPTH_CM", result.midpointCm());
        fields.put("FINAL_CONFIDENCE", result.confidence());
        fields.put("FINAL_RISK", result.riskLevel());
        fields.put("LIMITATIONS", texts(result.limitations()));
        log("DEPTH_SCENE_EVIDENCE", fields);
    }

    static void noReference() {
        log("DEPTH_CALCULATOR_SUMMARY", Map.of("DEPTH_OBJECTS_RETURNED", 0, "DEPTH_OBJECTS_ACCEPTED", 0,
                "CALCULATOR_ESTIMATED", false, "PRIMARY_INSUFFICIENT_REASON", "NO_REFERENCE_FOUND"));
    }

    // Model-written text is untrusted even after structural validation. Never access
    // actual credentials to redact it; omit payload/header echoes and redact token patterns.
    static String text(String value) {
        if (value == null) return "";
        if (value.matches("(?is).*(?:authorization|x[-_]goog[-_]api[-_]key|api[_ -]?key|access[_ -]?token|"
                + "GEMINI_API_KEY|MAPILLARY_ACCESS_TOKEN|GEOAPIFY_API_KEY|credential|password|"
                + "base64|data:image/|inline[_]?data|image[_ -]?(?:bytes|data)|request[_ -]?(?:headers|payload)|"
                + "[\"'](?:contents|data)[\"']\\s*:).*"))
            return "[OMITTED_SENSITIVE_OR_PAYLOAD_TEXT]";
        String safe = value.replaceAll("(?i)Bearer\\s+[^\\s,;\"']+", "Bearer [REDACTED]")
                .replaceAll("(?i)((?:token|secret|key)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;\"']+", "$1[REDACTED]")
                .replaceAll("https?://[^\\s\"']+", "[REDACTED_URL]")
                .replaceAll("[A-Za-z0-9_+/=-]{32,}", "[REDACTED_TOKEN]")
                .replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
        return safe.length() > 2000 ? safe.substring(0, 2000) + " [TRUNCATED]" : safe;
    }

    private static List<String> texts(List<String> values) {
        return values.stream().limit(12).map(DepthEvidenceDiagnostics::text).toList();
    }

    private static void log(String event, Map<String, ?> fields) {
        try {
            DepthDiagnostics.logger().info("{} requestId={} diagnostic={}", event,
                    requestId(), JSON.writeValueAsString(fields));
        } catch (Exception ignored) {
            // Diagnostics must never change calculation/persistence or echo a failing value.
            DepthDiagnostics.logger().warn("DEPTH_EVIDENCE_DIAGNOSTIC_UNAVAILABLE");
        }
    }

    private static String requestId() {
        String value = MDC.get("depthRequestId");
        return value != null && value.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                ? value : text(value);
    }
}
