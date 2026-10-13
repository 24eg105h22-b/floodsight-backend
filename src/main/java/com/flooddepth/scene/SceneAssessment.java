package com.flooddepth.scene;


/** Historical response labels retained for outward API and saved-report compatibility. */
public final class SceneAssessment {
    private SceneAssessment() { }
    public enum MatchDecision {
        LIKELY_SAME_SCENE, POSSIBLE_SAME_SCENE, INSUFFICIENT_EVIDENCE, LIKELY_DIFFERENT_SCENE
    }
    public enum Feature {
        BUILDING_FRONTAGE, WALL, GATE, ROAD_LAYOUT, KERB, DIVIDER, UTILITY_POLE,
        STEPS, PERMANENT_SIGN, INTERSECTION, DISTINCTIVE_ARCHITECTURE
    }
    public record MatchingLandmark(Feature feature, String floodImageEvidence, String referenceImageEvidence) { }
}
