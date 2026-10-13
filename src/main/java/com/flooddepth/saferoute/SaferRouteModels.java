package com.flooddepth.saferoute;

import java.time.Instant;
import java.util.List;

public final class SaferRouteModels {
    private SaferRouteModels() { }
    public record Point(double latitude,double longitude) {
        public Point {
            if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude < -90||latitude > 90||longitude < -180||longitude > 180)
                throw new IllegalArgumentException("Invalid coordinates");
        }
    }
    public record Request(Double startLatitude,Double startLongitude,String destinationText) { }
    public record Destination(String name,double latitude,double longitude) { }
    public record Candidate(String id,double distanceMeters,double durationSeconds,List<Point> geometry,String encodedPolyline) {
        public Candidate { geometry=List.copyOf(geometry); }
    }
    public enum Risk { LOW, MODERATE, HIGH, SEVERE }
    public enum AssessmentState { LIMITED_EVIDENCE, ASSESSED }
    public record Evidence(double latitude,double longitude,Instant observedAt,double depthMinCm,double depthMaxCm,
            String confidence,String riskLevel,String evidenceType) { }
    public record AssessedRoute(String id,double distanceMeters,double durationSeconds,Risk riskLevel,double riskScore,
            int floodEvidenceCount,int recentFloodEvidenceCount,int historicalFloodEvidenceCount,List<Point> geometry,
            String encodedPolyline,String summary,List<Evidence> evidence) {
        public AssessedRoute {
            // An empty observation set describes coverage, not a low flood risk.
            if (floodEvidenceCount == 0) riskLevel = null;
        }
        @com.fasterxml.jackson.annotation.JsonProperty
        public AssessmentState assessmentState() {
            return floodEvidenceCount == 0 ? AssessmentState.LIMITED_EVIDENCE : AssessmentState.ASSESSED;
        }
    }
    public record Response(Point start,Destination destination,String recommendedRouteId,List<AssessedRoute> routes,
            List<String> limitations) { }
}
