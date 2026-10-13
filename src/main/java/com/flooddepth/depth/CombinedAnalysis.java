package com.flooddepth.depth;

/** Validated response from the single combined image comparison. */
public record CombinedAnalysis(boolean sceneMatch, boolean floodWaterVisible, VisualDepthEstimate visualDepthEstimate) { }
