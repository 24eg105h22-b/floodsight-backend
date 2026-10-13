package com.flooddepth.depth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.ai.GeminiFloodVisionClient;
import com.flooddepth.ai.ValidatedAiCache;
import com.flooddepth.entity.FloodReport;
import com.flooddepth.exception.AiConfigurationException;

import com.flooddepth.mapillary.MapillaryService;
import com.flooddepth.scene.*;
import com.flooddepth.service.FloodReportService;
import com.flooddepth.service.ImageStorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.ArrayList;
import java.util.List;

/** Orchestrates existing services. Java validates visual estimates and owns scene eligibility and risk. */
@Service
public class DepthWorkflowService {
    private final ImageStorageService storage;
    private final MapillaryService mapillary;
    private final ObjectProvider<GeminiFloodVisionClient> provider;
    private final GeminiSceneImage preprocessing;
    private final Gemini503Retry retry;
    private final FloodReportService reports;
    private final ObjectMapper mapper;
    private final ValidatedAiCache cache;
    private VisualDepthEstimator visualEstimator;
    private VisualPerspectiveGrid visualGrid;
    @org.springframework.beans.factory.annotation.Autowired
    public void setVisualGrid(ImageRegistrationService registration,
            @org.springframework.beans.factory.annotation.Value("${depth.visual-grid.enabled:true}") boolean enabled,
            @org.springframework.beans.factory.annotation.Value("${depth.visual-grid.diagnostics.enabled:false}") boolean debug,
            @org.springframework.beans.factory.annotation.Value("${depth.visual-grid.diagnostics.directory:target/diagnostics/latest}") String directory) {
        visualGrid=new VisualPerspectiveGrid(registration,enabled,debug,directory);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public void setVisualEstimator(VisualDepthEstimator estimator) { this.visualEstimator=estimator; }
    @org.springframework.beans.factory.annotation.Autowired
    public DepthWorkflowService(ImageStorageService storage, MapillaryService mapillary,
            ObjectProvider<GeminiFloodVisionClient> provider, GeminiSceneImage preprocessing, Gemini503Retry retry,
            FloodReportService reports, ObjectMapper mapper, ValidatedAiCache cache) {
        this.storage=storage; this.mapillary=mapillary; this.provider=provider;
        this.preprocessing=preprocessing; this.retry=retry; this.reports=reports; this.mapper=mapper; this.cache=cache;
    }
    public DepthWorkflowService(ImageStorageService storage, MapillaryService mapillary,
            ObjectProvider<GeminiFloodVisionClient> provider, GeminiSceneImage preprocessing, Gemini503Retry retry,
            FloodReportService reports, ObjectMapper mapper) {
        this(storage,mapillary,provider,preprocessing,retry,reports,mapper,new ValidatedAiCache());
    }
    public DepthResult estimate(MultipartFile file, double latitude, double longitude) {
        DepthDiagnostics.stage(DepthDiagnostics.Stage.IMAGE_VALIDATION);
        MapillaryService.validateCoordinates(latitude, longitude);
        if (file == null || file.isEmpty() || file.getSize() > 10*1024*1024) throw new IllegalArgumentException("Invalid image");
        String name = file.getOriginalFilename();
        String mime = name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png"
                : name != null && name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.jpe?g$") ? "image/jpeg" : "";
        if (mime.isEmpty()) throw new IllegalArgumentException("Unsupported image");
        GeminiSceneImage.Prepared flood;
        byte[] originalFlood;
        try (var input = file.getInputStream()) { originalFlood=input.readNBytes(10*1024*1024+1); flood = preprocessing.prepare(originalFlood, mime); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("Invalid image"); }
        DepthDiagnostics.logger().info("DEPTH_IMAGE floodWidth={} floodHeight={} floodBytes={}",
                flood.transmission().transmittedWidth(), flood.transmission().transmittedHeight(), flood.bytes().length);
        DepthDiagnostics.stage(DepthDiagnostics.Stage.CONFIGURATION);
        GeminiFloodVisionClient gemini = provider.getIfAvailable();
        if (gemini == null) throw new AiConfigurationException("Gemini unavailable");
        gemini.ensureConfigured();
        DepthDiagnostics.stage(DepthDiagnostics.Stage.STORAGE);
        String path = storage.storeImage(file).imagePath();
        DepthDiagnostics.stage(DepthDiagnostics.Stage.MAPILLARY_LOOKUP);
        var discovery = mapillary.discoverReferences(latitude, longitude);
        // Deterministic existing discovery ranking chooses one reference, without assuming scene identity.
        var reference = discovery.candidates().stream().findFirst().orElse(null);
        if (reference == null) {
            DepthEvidenceDiagnostics.noReference();
            DepthDiagnostics.stage(DepthDiagnostics.Stage.SERIALIZATION);
            return insufficient(latitude, longitude,
                    List.of("No suitable historical street images were found near these coordinates."));
        }
        DepthDiagnostics.logger().info("DEPTH_REFERENCE candidates={} examined={} id={} rank={} distance={} capture={} heading={}",
                discovery.candidates().size(), discovery.candidatesExamined(), reference.imageId(), reference.rank(),
                reference.distanceMeters(), reference.capturedAt(), reference.compassAngle());
        DepthDiagnostics.stage(DepthDiagnostics.Stage.REFERENCE_DOWNLOAD);
        var image = mapillary.getReferenceImage(reference.imageId());
        var dry = preprocessing.prepare(image.bytes(), image.mediaType().toString());
        DepthDiagnostics.logger().info("DEPTH_IMAGE requestId={} referenceWidth={} referenceHeight={} referenceBytes={}",
                org.slf4j.MDC.get("depthRequestId"), dry.transmission().transmittedWidth(),
                dry.transmission().transmittedHeight(), dry.bytes().length);
        DepthDiagnostics.stage(DepthDiagnostics.Stage.COMBINED_ANALYSIS);
        var comparison=visualGrid==null?VisualPerspectiveGrid.Comparison.omitted("NOT_CONFIGURED"):visualGrid.prepareComparison(flood,dry);
        if(visualGrid==null)DepthDiagnostics.logger().info("REGISTERED_COMPARISON generated=false registrationQuality=UNUSABLE reason=NOT_CONFIGURED metricScale=false");
        var sentFlood=flood;var sentDry=dry;
        var key = ValidatedAiCache.key("COMBINED_ANALYSIS", ValidatedAiCache.hash(originalFlood), reference.imageId(), image.bytes(),
                CombinedAnalysisPrompt.VERSION + ":" + ValidatedAiCache.hash((CombinedAnalysisPrompt.INSTRUCTIONS + CombinedAnalysisPrompt.schema())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                gemini.cacheConfigurationId()+":"+VisualPerspectiveGrid.COMPARISON_VERSION+":"+
                        (comparison.generated()?ValidatedAiCache.hash(comparison.bytes()):"none"),
                sentFlood.bytes(), sentDry.bytes(), sentFlood.mimeType(), sentDry.mimeType());
        var sent = new java.util.concurrent.atomic.AtomicBoolean();
        var combined = cache.getOrCompute(key, CombinedAnalysis.class, () -> {
            sent.set(true);
            return retry.execute(Gemini503Retry.AiStage.COMBINED_ANALYSIS,
                    () -> comparison.generated()
                            ?gemini.analyzeSceneAndDepth(sentFlood.bytes(),sentFlood.mimeType(),sentDry.bytes(),sentDry.mimeType(),comparison.bytes(),"image/png")
                            :gemini.analyzeSceneAndDepth(sentFlood.bytes(),sentFlood.mimeType(),sentDry.bytes(),sentDry.mimeType())).value();
        });
        DepthDiagnostics.logger().info("AI_CACHE stage=COMBINED_ANALYSIS hit={}", !sent.get());
        if(!sent.get())DepthDiagnostics.logger().info("REGISTERED_COMPARISON overlaySentToGemini=false reason=CACHE_HIT");
        DepthDiagnostics.stage(DepthDiagnostics.Stage.SCENE_POLICY);
        var calculated = visualEstimator.chooseOverlap(combined);
        boolean visualUsed = calculated.estimated();
        String method = visualUsed ? "DIRECT_VISUAL_ESTIMATE" : null;
        String sceneState = combined.sceneMatch() ? "POSSIBLE_SCENE_MATCH" : "UNSUITABLE_REFERENCE";
        DepthEvidenceDiagnostics.overlap(combined, calculated);
        var resultReference = new DepthResult.Reference(reference.imageId(), reference.capturedAt(), reference.latitude(),
                reference.longitude(), reference.distanceMeters(), "/api/mapillary/reference/" + reference.imageId() + "/image",
                reference.creator(), reference.attributionUrl());
        DepthDiagnostics.stage(DepthDiagnostics.Stage.CALCULATION);
        var visualEvidence = visualUsed ? new DepthResult.VisualEvidence(calculated.midpointCm(), calculated.confidence(),
                combined.visualDepthEstimate().reasoning(), List.of(VisualDepthEstimator.NOTICE)) : null;
        var calibrationEvidence = List.<DepthResult.CalibrationEvidence>of();
        DepthDiagnostics.logger().info("DEPTH_CALCULATED estimated={} acceptedObjects={}", calculated.estimated(), calculated.evidence().size());
        var scene = new DepthResult.SceneMatch(combined.sceneMatch() ? SceneAssessment.MatchDecision.LIKELY_SAME_SCENE
                : SceneAssessment.MatchDecision.LIKELY_DIFFERENT_SCENE, null, sceneState, calculated.estimated(), List.of());
        var limitations = new ArrayList<>(calculated.limitations());
        Long reportId = null;
        if (calculated.estimated()) {
            DepthDiagnostics.stage(DepthDiagnostics.Stage.PERSISTENCE);
            FloodReport report = new FloodReport();
            report.setLatitude(latitude); report.setLongitude(longitude); report.setImagePath(path);
            report.setLocation("Photo coordinates: " + latitude + ", " + longitude);
            report.setEstimatedDepthCm(calculated.midpointCm()); report.setDepthMinCm(calculated.minCm()); report.setDepthMaxCm(calculated.maxCm());
            report.setDepthConfidence(calculated.confidence()); report.setRiskLevel(calculated.riskLevel());
            report.setRiskRangeCrossesThreshold(calculated.crossesThreshold());
            report.setMapillaryImageId(reference.imageId()); report.setSceneMatchDecision(scene.decision().name()); report.setSceneMatchConfidence(null);
            report.setReferenceObjectType("DIRECT_VISUAL_ESTIMATE");
            try { var storedEvidence = mapper.createArrayNode();
                if(visualUsed) {
                    var record=mapper.createObjectNode();record.put("method","DIRECT_VISUAL_ESTIMATE");
                    record.set("visualEstimate",mapper.valueToTree(visualEvidence));storedEvidence.add(record);
                }
                report.setDepthEvidenceJson(mapper.writeValueAsString(storedEvidence)); report.setDepthLimitationsJson(mapper.writeValueAsString(limitations)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("Could not prepare estimate for storage"); }
            reportId = reports.saveFloodReport(report).getId();
        }
        DepthDiagnostics.stage(DepthDiagnostics.Stage.SERIALIZATION);
        return new DepthResult(calculated.estimated() ? "ESTIMATED" : "INSUFFICIENT_EVIDENCE", latitude, longitude,
                resultReference, scene, calculated.minCm(), calculated.maxCm(), calculated.midpointCm(), calculated.confidence(),
                calculated.riskLevel(), calculated.crossesThreshold(), calculated.evidence(), List.copyOf(limitations), reportId,calibrationEvidence,method,visualEvidence);
    }

    private static DepthResult insufficient(double lat, double lon, List<String> limitations) {
        return new DepthResult("INSUFFICIENT_EVIDENCE", lat, lon, null, null, null, null, null,
                "INSUFFICIENT", null, false, List.of(), limitations, null);
    }
}
