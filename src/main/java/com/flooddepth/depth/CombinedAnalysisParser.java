package com.flooddepth.depth;

import com.flooddepth.ai.AiResponseParser;
import com.flooddepth.exception.GeminiFailureException;
import java.util.Set;

/** Strict validation of the five-field production comparison contract. */
public final class CombinedAnalysisParser {
    private CombinedAnalysisParser() { }
    /** Production contract. Experimental object/calibration fields are ignored, never prerequisites. */
    public static CombinedAnalysis parseOverlap(AiResponseParser reader, String json) {
        var root = reader.readStrictJson(json);
        if (!root.isObject()) throw overlapInvalid("response", "WRONG_TYPE", "OBJECT", root);
        for (String field : java.util.List.of("sceneMatch", "floodWaterVisible", "approxDepthCm", "confidence", "reasoning"))
            if (!root.has(field)) throw overlapInvalid(field, "MISSING_FIELD", "required", null);
        for (String field : java.util.List.of("sceneMatch", "floodWaterVisible"))
            if (!root.get(field).isBoolean()) throw overlapInvalid(field, "WRONG_TYPE", "BOOLEAN", root.get(field));
        var confidence = root.get("confidence");
        if (!confidence.isTextual()) throw overlapInvalid("confidence", "WRONG_TYPE", "STRING", confidence);
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(confidence.textValue()))
            throw overlapInvalid("confidence", "INVALID_ENUM", "LOW/MEDIUM/HIGH", confidence);
        var reasoning = root.get("reasoning");
        if (!reasoning.isTextual() || reasoning.textValue().isBlank())
            throw overlapInvalid("reasoning", "WRONG_TYPE", "non-empty STRING", reasoning);
        var number = root.get("approxDepthCm");
        // Null explicitly represents inability to estimate, not a made-up zero measurement.
        boolean possible = !number.isNull();
        if (possible && !number.isNumber()) throw overlapInvalid("approxDepthCm", "WRONG_TYPE", "NUMBER or null", number);
        if (possible && (!Double.isFinite(number.doubleValue()) || number.doubleValue() < 0
                || number.doubleValue() > VisualDepthEstimate.MAX_DEPTH_CM))
            throw overlapInvalid("approxDepthCm", "OUT_OF_RANGE", "finite non-negative depth within application ceiling", number);
        boolean same = root.get("sceneMatch").booleanValue(), water = root.get("floodWaterVisible").booleanValue();
        var visual = new VisualDepthEstimate(possible, possible ? number.doubleValue() : 0,
                confidence.textValue(), reasoning.textValue(), java.util.List.of());
        return new CombinedAnalysis(same, water, visual);
    }
    private static GeminiFailureException overlapInvalid(String field, String code, String expected,
            com.fasterxml.jackson.databind.JsonNode value) {
        // Arbitrary provider strings and optional fields are never printed.
        String safe = value == null ? "missing" : value.isNumber() || value.isBoolean() || value.isNull()
                ? value.toString() : value.getNodeType().name();
        DepthDiagnostics.logger().warn("OVERLAP_VALIDATION_FAILED field={} code={} expected={} actualSafeValue={}",
                field, code, expected, safe);
        return invalid();
    }
    private static GeminiFailureException invalid() {
        return new GeminiFailureException(GeminiFailureException.Code.GEMINI_RESPONSE_VALIDATION_FAILED);
    }
}
