package com.flooddepth.service;

import com.flooddepth.entity.RiskLevel;
import org.springframework.stereotype.Service;

@Service
public class FloodDepthCalculationService {

    private static final double LOW_MAX_DEPTH_CM = 15.0;
    private static final double MODERATE_MAX_DEPTH_CM = 30.0;
    private static final double HIGH_MAX_DEPTH_CM = 60.0;

    public RiskLevel determineRiskLevel(double estimatedDepthCm) {
        if (!Double.isFinite(estimatedDepthCm) || estimatedDepthCm < 0) {
            throw new IllegalArgumentException("estimatedDepthCm must be 0 or greater");
        }

        if (estimatedDepthCm <= LOW_MAX_DEPTH_CM) {
            return RiskLevel.LOW;
        }
        if (estimatedDepthCm <= MODERATE_MAX_DEPTH_CM) {
            return RiskLevel.MODERATE;
        }
        if (estimatedDepthCm <= HIGH_MAX_DEPTH_CM) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.SEVERE;
    }
}
