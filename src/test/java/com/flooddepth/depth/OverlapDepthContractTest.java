package com.flooddepth.depth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flooddepth.ai.AiResponseParser;
import com.flooddepth.exception.GeminiFailureException;
import com.flooddepth.service.FloodDepthCalculationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OverlapDepthContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    final AiResponseParser reader=new AiResponseParser(mapper);
    final VisualDepthEstimator estimator=new VisualDepthEstimator(new FloodDepthCalculationService());
    ObjectNode json() throws Exception {return (ObjectNode)mapper.readTree(CombinedFixtures.overlapJson());}
    CombinedAnalysis parse(ObjectNode n) {return CombinedAnalysisParser.parseOverlap(reader,n.toString());}
    @Test void fiveFieldsAloneRoundBeforeJavaRiskAndNeedNoLandmarksOrCalibration() throws Exception {
        var n=json();n.put("approxDepthCm",29.8);n.put("confidence","HIGH");
        var a=parse(n);var result=estimator.chooseOverlap(a);
        assertTrue(result.estimated());assertEquals(30,result.midpointCm());assertEquals("MEDIUM",result.confidence());
        assertEquals(new FloodDepthCalculationService().determineRiskLevel(30),result.riskLevel());
        assertTrue(a.sceneMatch()); assertTrue(a.floodWaterVisible());
        assertEquals(List.of(VisualDepthEstimator.NOTICE),result.limitations());
    }
    @Test void malformedOptionalDiagnosticsAndProviderRiskCannotBlockOrControlValidDepth() throws Exception {
        var n=json();n.put("referenceCandidates","bad optional data");n.putNull("sceneCalibration");
        n.put("commonPermanentFeature",false);n.put("risk","SEVERE");n.put("limitations",42);
        var a=estimator.chooseOverlap(parse(n));var b=estimator.chooseOverlap(parse(json()));
        assertTrue(a.estimated());assertEquals(b.midpointCm(),a.midpointCm());assertEquals(b.riskLevel(),a.riskLevel());
    }
    @ParameterizedTest @ValueSource(strings={"sceneMatch","floodWaterVisible","approxDepthCm","confidence","reasoning"})
    void missingRequiredFieldHasSafeExactDiagnostic(String field) throws Exception {
        var n=json();n.remove(field);
        try(var log=new DepthDiagnosticCapture()) {
            assertThrows(GeminiFailureException.class,()->parse(n));
            assertTrue(log.messages().contains("field="+field));assertTrue(log.messages().contains("code=MISSING_FIELD"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"sceneMatch","floodWaterVisible"})
    void booleanFlagsNeverCoerceNumbersOrStrings(String field) throws Exception {
        for(String value:List.of("1","\"true\"","null","[]")) {
            var n=json();n.set(field,mapper.readTree(value));assertThrows(GeminiFailureException.class,()->parse(n));
        }
    }
    @ParameterizedTest @ValueSource(strings={"-0.01","501","1e999","\"28\"","true","{}","[]"})
    void unsafeNumericValuesNeverBecomeDepth(String value) throws Exception {
        var n=json();n.set("approxDepthCm",mapper.readTree(value));assertThrows(GeminiFailureException.class,()->parse(n));
    }
    @ParameterizedTest @ValueSource(strings={"LOW","MEDIUM","HIGH"})
    void acceptedConfidenceValuesKeepVisualConfidenceAtMostMedium(String confidence) throws Exception {
        var n=json();n.put("confidence",confidence);
        assertEquals(confidence.equals("HIGH")?"MEDIUM":confidence,estimator.chooseOverlap(parse(n)).confidence());
    }
    @ParameterizedTest @ValueSource(strings={"\"medium\"","null","1","[]"})
    void invalidConfidenceRejectedWithoutLoggingArbitraryProviderStrings(String value) throws Exception {
        var n=json();n.set("confidence",mapper.readTree(value));assertThrows(GeminiFailureException.class,()->parse(n));
    }
    @Test void failureLogDoesNotEchoProviderTextOrExtraPayloads() throws Exception {
        var n=json();n.put("confidence","mock-private-credential");n.put("imageBytes","mock-private-image-data");
        try(var log=new DepthDiagnosticCapture()) {
            assertThrows(GeminiFailureException.class,()->parse(n));
            assertTrue(log.messages().contains("field=confidence code=INVALID_ENUM"));
            assertFalse(log.messages().contains("mock-private"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"null","\"\"","\"   \"","42","false","[]"})
    void reasoningMustBeNonemptyText(String value) throws Exception {
        var n=json();n.set("reasoning",mapper.readTree(value));assertThrows(GeminiFailureException.class,()->parse(n));
    }
    @Test void optionalDiagnosticLengthRulesDoNotRejectUsableReasoning() throws Exception {
        var n=json();n.put("reasoning","Useful projected water contact. ".repeat(90));
        assertTrue(estimator.chooseOverlap(parse(n)).estimated());
    }
    @ParameterizedTest @ValueSource(strings={"sceneMatch","floodWaterVisible"})
    void negativeSceneOrWaterFlagNeverReturnsDepthEvenIfNumberValid(String field) throws Exception {
        var n=json();n.put(field,false);var result=estimator.chooseOverlap(parse(n));
        assertFalse(result.estimated());assertNull(result.midpointCm());assertNull(result.riskLevel());
    }
    @Test void explicitVisualInabilityAndZeroDepthAreDifferent() throws Exception {
        var n=json();n.putNull("approxDepthCm");n.put("reasoning","Waterline geometry cannot support a reasonable estimate.");
        assertFalse(estimator.chooseOverlap(parse(n)).estimated());
        n.put("approxDepthCm",0);assertTrue(estimator.chooseOverlap(parse(n)).estimated());
    }
    @ParameterizedTest @ValueSource(strings={"```json\n{}\n```","{} prose","{\"sceneMatch\":true,\"sceneMatch\":false}","not json"})
    void fencesTrailingContentAndDuplicateKeysRemainInvalid(String value) {
        assertThrows(GeminiFailureException.class,()->CombinedAnalysisParser.parseOverlap(reader,value));
    }
}
