package com.flooddepth.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.flooddepth.exception.GeminiFailureException;
import static com.flooddepth.exception.GeminiFailureException.Code.*;
import org.springframework.stereotype.Component;

@Component
public class AiResponseParser {
    private final ObjectMapper objectMapper;
    public AiResponseParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    public JsonNode readStrictJson(String json) {
        if (json == null || json.isBlank() || json.length() > 32_000)
            throw new GeminiFailureException(GEMINI_MALFORMED_RESPONSE);
        try {
            JsonNode node = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(json);
            if (node == null) throw new GeminiFailureException(GEMINI_MALFORMED_RESPONSE);
            return node;
        } catch (java.io.IOException exception) {
            throw new GeminiFailureException(GEMINI_MALFORMED_RESPONSE);
        }
    }

}
