package com.flooddepth.depth;

import java.util.List;

/** Approximate visual result; not a calibrated measurement. */
public record VisualDepthEstimate(boolean possible, double approxDepthCm, String confidence,
        String reasoning, List<String> limitations) {
    // Existing application validation ceiling, never a target estimate.
    public static final double MAX_DEPTH_CM = 500;
}
