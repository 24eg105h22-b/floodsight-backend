package com.flooddepth.depth;
import com.flooddepth.scene.SceneTestFixtures;
import com.flooddepth.service.FloodDepthCalculationService;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
/** Prompt contracts and deterministic validation, not live visual accuracy. */
class VisualDepthPromptTest {
    final String prompt=CombinedAnalysisPrompt.INSTRUCTIONS;
    final String normalized=prompt.replaceAll("\\s+"," ");
    @Test void shortPromptPreservesOriginalSceneCheckAndConditionalOverlay() {
        assertTrue(normalized.split(" ").length<450);
        for(String cue:List.of("ORIGINAL two images","permanent structures","Set sceneMatch false",
                "if visually consistent, the registered overlay","ignore misleading alignment",
                "no metric calibration","absence or failure must not block estimation"))assertTrue(normalized.contains(cue),cue);
    }
    @Test void dryOnlyObjectsSupplyApproximateScaleWithoutMandatoryObjectsOrCalculations() {
        for(String cue:List.of("complete dry image","approximate real-world scale","This list is not restrictive",
                "common-size knowledge","Use several useful clues together","Vehicles may be dry-only scale references",
                "not an observed submerged vehicle","do not force one primary object, formula or separate zone calculations"))
            assertTrue(normalized.contains(cue),cue);
    }
    @Test void projectedWaterContactAndPerspectiveDriveDepthRatherThanCoverage() {
        for(String cue:List.of("where would water reach on them?","wheel centres","lower vehicle bodies","divider tops",
                "wall/pillar bases","Prefer nearby scale clues","perspective, camera height/angle, object distance and road slope/camber",
                "VERTICAL water depth","Horizontal flood coverage indicates extent, not depth"))assertTrue(normalized.contains(cue),cue);
    }
    @Test void onlyMinimalFieldsAreMandatoryWithoutRiskAuthorityOrFixedAnswers() throws Exception {
        for(String field:List.of("commonPermanentFeature", "floodWaterlineDescription", "dryImageCorrespondingLevel", "relativeVehicleScaleReasoning"))assertFalse(prompt.contains(field));
        assertTrue(normalized.contains("No calibration, physical prior, web lookup or mandatory diagnostic fields are required"));
        assertTrue(normalized.contains("Do not choose application risk"));
        String source=Files.readString(Path.of("src/main/java/com/flooddepth/depth/CombinedAnalysisPrompt.java"));
        assertFalse(java.util.regex.Pattern.compile("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:cm|centimetres|metres|meters)\\b").matcher(source).find());
        assertFalse(prompt.contains("1230249358212792"));
        assertTrue(normalized.contains("No target depth or fixed object size is supplied"));
    }
}
