package com.flooddepth.depth;

import com.flooddepth.service.FloodDepthCalculationService;
import org.springframework.stereotype.Service;
import java.util.*;

/** Validates the approximate visual number and delegates risk calculation to Java. */
@Service
public class VisualDepthEstimator {
    public static final String NOTICE = "Image-based approximate estimate; not an instrument measurement.";
    private final FloodDepthCalculationService risk;
    public VisualDepthEstimator(FloodDepthCalculationService risk) {
        this.risk = risk;
    }
    /** Normal approximate workflow: only scene correspondence, water and the validated visual number. */
    public Calculation chooseOverlap(CombinedAnalysis combined) {
        if (!combined.sceneMatch())
            return insufficient("A matching dry road reference could not be established.");
        if (!combined.floodWaterVisible()) return insufficient("Floodwater could not be identified in the supplied image.");
        var visual = combined.visualDepthEstimate();
        if (visual == null || !visual.possible() || !Double.isFinite(visual.approxDepthCm()) || visual.approxDepthCm() < 0
                || visual.approxDepthCm() > VisualDepthEstimate.MAX_DEPTH_CM
                || visual.confidence() == null || !Set.of("LOW", "MEDIUM", "HIGH").contains(visual.confidence())
                || visual.reasoning() == null || visual.reasoning().isBlank())
            return insufficient("Visual depth could not reasonably be estimated from the two images.");
        double cm = Math.round(visual.approxDepthCm());
        String confidence = visual.confidence().equals("HIGH") ? "MEDIUM" : visual.confidence();
        return new Calculation(true, cm, cm, cm, confidence,
                risk.determineRiskLevel(cm), false, List.of(), List.of(NOTICE));
    }
    public record Calculation(boolean estimated, Double minCm, Double maxCm, Double midpointCm,
            String confidence, com.flooddepth.entity.RiskLevel riskLevel, boolean crossesThreshold,
            List<DepthResult.Evidence> evidence, List<String> limitations) { }
    private static Calculation insufficient(String reason) {
        return new Calculation(false,null,null,null,"INSUFFICIENT",null,false,List.of(),List.of(reason));
    }
}
