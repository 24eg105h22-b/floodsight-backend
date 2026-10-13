package com.flooddepth.depth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Fixed stages/scalars plus allowlisted, sanitized parsed evidence diagnostics. */
public final class DepthDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(DepthDiagnostics.class);
    private DepthDiagnostics() { }
    public enum Stage { REQUEST, IMAGE_VALIDATION, CONFIGURATION, STORAGE,
        REFERENCE_DOWNLOAD, DEPTH_PARSER, MAPILLARY_LOOKUP,
        COMBINED_ANALYSIS, SCENE_POLICY, CALCULATION, PERSISTENCE, SERIALIZATION }
    public static void stage(Stage stage) {
        MDC.put("depthStage", stage.name());
        LOG.info("DEPTH_WORKFLOW requestId={} stage={}", MDC.get("depthRequestId"), stage);
    }
    public static void failed(String reason, Integer providerStatus) {
        LOG.warn("DEPTH_WORKFLOW_FAILED requestId={} stage={} reason={} providerHttpStatus={}",
                MDC.get("depthRequestId"), MDC.get("depthStage"), reason, providerStatus);
    }
    public static Logger logger() { return LOG; }
}
