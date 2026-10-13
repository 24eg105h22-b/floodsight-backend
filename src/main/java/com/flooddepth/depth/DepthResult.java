package com.flooddepth.depth;

import com.flooddepth.entity.RiskLevel;
import com.flooddepth.scene.SceneAssessment;
import java.time.Instant;
import java.util.List;

public record DepthResult(String status, double latitude, double longitude, Reference mapillaryReference,
        SceneMatch sceneMatch, Double estimatedDepthMinCm, Double estimatedDepthMaxCm,
        Double representativeDepthCm, String confidence, RiskLevel riskLevel, boolean riskRangeCrossesThreshold,
        List<Evidence> referenceEvidence, List<String> limitations, Long reportId,
        List<CalibrationEvidence> calibrationEvidence, String estimationMethod, VisualEvidence visualEvidence) {
    public DepthResult(String status,double latitude,double longitude,Reference reference,SceneMatch scene,
            Double min,Double max,Double representative,String confidence,RiskLevel risk,boolean crosses,
            List<Evidence> evidence,List<String> limitations,Long reportId,List<CalibrationEvidence> calibration) {
        this(status,latitude,longitude,reference,scene,min,max,representative,confidence,risk,crosses,evidence,limitations,reportId,
                calibration,null,null);
    }
    @com.fasterxml.jackson.annotation.JsonProperty("approxDepthCm")
    public Double approxDepthCm() { return representativeDepthCm==null?null:(double)Math.round(representativeDepthCm); }
    public record VisualEvidence(double approxDepthCm,String confidence,String reasoning,List<String> limitations) { }
    public DepthResult(String status,double latitude,double longitude,Reference reference,SceneMatch scene,
            Double min,Double max,Double representative,String confidence,RiskLevel risk,boolean crosses,
            List<Evidence> evidence,List<String> limitations,Long reportId) {
        this(status,latitude,longitude,reference,scene,min,max,representative,confidence,risk,crosses,evidence,limitations,reportId,List.of());
    }
    public record CalibrationEvidence(String measurementId,String description,double derivedDepthMinCm,
            double derivedDepthMaxCm,String confidence,String evidenceSummary,List<String> limitations) { }
    public record Reference(String imageId, Instant capturedAt, double latitude, double longitude,
            double distanceMeters, String imageUrl, String creator, String attributionUrl) { }
    public record SceneMatch(SceneAssessment.MatchDecision decision, Double confidence, String sceneState,
            boolean safeForDepthComparison, List<SceneAssessment.MatchingLandmark> matchingLandmarks) { }
    public record Evidence(String objectId, String type, String description,
            double physicalDimensionMinCm, double physicalDimensionMaxCm, double submergedFractionMin,
            double submergedFractionMax, double derivedDepthMinCm, double derivedDepthMaxCm,
            String evidenceSummary, String priorJustification, List<String> limitations) { }
}
