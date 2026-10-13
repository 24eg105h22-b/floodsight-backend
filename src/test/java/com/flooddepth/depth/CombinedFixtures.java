package com.flooddepth.depth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.ai.AiResponseParser;

public final class CombinedFixtures {
    private CombinedFixtures() { }
    public static String overlapJson() {
        return "{\"sceneMatch\":true,\"floodWaterVisible\":true,\"approxDepthCm\":28.4,\"confidence\":\"MEDIUM\",\"reasoning\":\"Local projected water contact provides approximate scale.\"}";
    }
    public static CombinedAnalysis overlapAssessment() {
        return CombinedAnalysisParser.parseOverlap(new AiResponseParser(new ObjectMapper()), overlapJson());
    }
}
