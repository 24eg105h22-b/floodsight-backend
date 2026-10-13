package com.flooddepth.depth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.ai.GeminiFloodVisionClient;
import com.flooddepth.ai.GeminiRateLimit;
import com.flooddepth.entity.FloodReport;
import com.flooddepth.exception.*;
import com.flooddepth.mapillary.*;
import com.flooddepth.scene.*;
import com.flooddepth.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DepthWorkflowServiceTest {
    @TempDir Path dir;
    final MapillaryService mapillary=mock(MapillaryService.class);
    final GeminiFloodVisionClient gemini=mock(GeminiFloodVisionClient.class);
    final FloodReportService reports=mock(FloodReportService.class);
    @SuppressWarnings("unchecked") final ObjectProvider<GeminiFloodVisionClient> provider=mock(ObjectProvider.class);
    DepthWorkflowService service;
    final MockMultipartFile image=new MockMultipartFile("file","flood.png","image/png",SceneTestFixtures.png());
    @BeforeEach void setup() {
        when(provider.getIfAvailable()).thenReturn(gemini);
        service=new DepthWorkflowService(new ImageStorageService(dir.toString()),mapillary,provider,
                new GeminiSceneImage(1280),new Gemini503Retry(),reports,new ObjectMapper());
        service.setVisualEstimator(new VisualDepthEstimator(new FloodDepthCalculationService()));
        when(mapillary.discoverReferences(10,20)).thenReturn(dataset(3));
        when(mapillary.getReferenceImage(anyString())).thenReturn(new MapillaryService.ReferenceImage(SceneTestFixtures.png(),MediaType.IMAGE_PNG));
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(CombinedFixtures.overlapAssessment());
        when(reports.saveFloodReport(any())).thenAnswer(call->{FloodReport report=call.getArgument(0); report.setId(42L); return report;});
    }
    MapillaryReferences dataset(int count) {
        var refs=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(i->SceneTestFixtures.reference(String.valueOf(i),i)).toList();
        return new MapillaryReferences(refs,10,20,250,count,count,0,count,0,false,"dataset","screening","order","Unverified",List.of());
    }
    void assertOneCombinedCall() {
        assertEquals(1,org.mockito.Mockito.mockingDetails(gemini).getInvocations().stream()
                .filter(call->call.getMethod().getName().equals("analyzeSceneAndDepth")).count());
        verify(mapillary,never()).closeSequenceNeighbors(any(),anyDouble(),anyDouble());
    }
    @Test void optionalComparisonReusesRegistrationAndSendsTwoUntouchedOriginalsPlusOverlayInOneCall() throws Exception {
        byte[] original=CalibrationFixtures.bytes(CalibrationFixtures.image());
        var input=new MockMultipartFile("file","flood.png","image/png",original);
        when(mapillary.getReferenceImage(anyString())).thenReturn(new MapillaryService.ReferenceImage(original,MediaType.IMAGE_PNG));
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(CalibrationFixtures.identityRegistration());
        service.setVisualGrid(registration,true,false,dir.toString());
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString(),any(),eq("image/png"))).thenAnswer(call->{
            for(int index:new int[]{0,2}) {
                byte[] wire=call.getArgument(index);assertArrayEquals(original,wire);
                var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(wire));
                assertEquals(320,decoded.getWidth());assertEquals(240,decoded.getHeight());
            }
            assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(call.getArgument(4))));
            return CombinedFixtures.overlapAssessment();
        });
        var result=service.estimate(input,10,20);assertEquals("ESTIMATED",result.status());assertEquals(28,result.approxDepthCm());
        verify(registration,times(1)).register(any(),any(),any());assertOneCombinedCall();
        verify(gemini,never()).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
        
    }
    @Test void unusableGridStillUsesOriginalInputsAndReturnsNormalVisualResult() {
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenThrow(new IllegalArgumentException("fixture"));
        service.setVisualGrid(registration,true,false,dir.toString());
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenAnswer(call->{
            assertArrayEquals(image.getBytes(),call.getArgument(0));assertArrayEquals(SceneTestFixtures.png(),call.getArgument(2));
            return CombinedFixtures.overlapAssessment();
        });
        assertEquals("DIRECT_VISUAL_ESTIMATE",service.estimate(image,10,20).estimationMethod());assertOneCombinedCall();
    }
    @Test void registeredOverlayCacheHitsAvoidASecondProviderRequestAndOmittedOverlayHasDifferentKey() {
        byte[] original=CalibrationFixtures.bytes(CalibrationFixtures.image());
        var input=new MockMultipartFile("file","flood.png","image/png",original);
        when(mapillary.getReferenceImage(anyString())).thenReturn(new MapillaryService.ReferenceImage(original,MediaType.IMAGE_PNG));
        var registration=mock(ImageRegistrationService.class);
        when(registration.register(any(),any(),any())).thenReturn(CalibrationFixtures.identityRegistration());
        service.setVisualGrid(registration,true,false,dir.toString());
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString(),any(),eq("image/png"))).thenReturn(CombinedFixtures.overlapAssessment());
        assertEquals("ESTIMATED",service.estimate(input,10,20).status());
        try(var log=new DepthDiagnosticCapture()) {
            assertEquals("ESTIMATED",service.estimate(input,10,20).status());
            assertTrue(log.messages().contains("overlaySentToGemini=false reason=CACHE_HIT"));
        }
        verify(gemini,times(1)).analyzeSceneAndDepth(any(),anyString(),any(),anyString(),any(),eq("image/png"));
        verify(gemini,never()).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
        when(registration.register(any(),any(),any())).thenReturn(ImageRegistrationService.unusable());
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(CombinedFixtures.overlapAssessment());
        assertEquals("ESTIMATED",service.estimate(input,10,20).status());
        verify(gemini,times(1)).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
    }
    @Test void zeroObjectVisualResultPersistsAndUsesExactlyOneImageRequest() throws Exception {
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(CombinedFixtures.overlapAssessment());
        var r=service.estimate(image,10,20);
        assertEquals("ESTIMATED",r.status());assertEquals("DIRECT_VISUAL_ESTIMATE",r.estimationMethod());assertEquals(28,r.approxDepthCm());
        assertEquals("MEDIUM",r.confidence());assertEquals("MODERATE",r.riskLevel().name());assertTrue(r.referenceEvidence().isEmpty());
        assertFalse(r.limitations().stream().anyMatch(l->l.contains("could not establish a reliable physical scale")));
        assertNotNull(r.visualEvidence());assertOneCombinedCall();
        var capture=org.mockito.ArgumentCaptor.forClass(FloodReport.class);verify(reports).saveFloodReport(capture.capture());
        assertEquals(28,capture.getValue().getEstimatedDepthCm());assertEquals("DIRECT_VISUAL_ESTIMATE",capture.getValue().getReferenceObjectType());
        assertTrue(capture.getValue().getDepthEvidenceJson().contains("visualEstimate"));
        String publicJson=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(r);
        for(String field:List.of("commonPermanentFeature","floodWaterlineDescription","dryImageCorrespondingLevel")) {
            assertFalse(publicJson.contains("\""+field+"\""));
            assertFalse(capture.getValue().getDepthEvidenceJson().contains("\""+field+"\""));
        }
    }



    @Test void invisibleFloodWaterBlocksEvenGoodObjectsWithoutOptionalLookups() {
        var v=CombinedFixtures.overlapAssessment();
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(
                new CombinedAnalysis(v.sceneMatch(),false,v.visualDepthEstimate()));
        try(var log=new DepthDiagnosticCapture()) {
            assertEquals("INSUFFICIENT_EVIDENCE",service.estimate(image,10,20).status());
            assertFalse(log.entries("DEPTH_SCENE_EVIDENCE").get(0).path("JAVA_ACCEPTED").asBoolean());
            assertFalse(log.entries("DEPTH_SCENE_EVIDENCE").get(0).path("FLOOD_WATER_VISIBLE").asBoolean());
        }
        verifyNoInteractions(reports);assertOneCombinedCall();
    }
    @Test void rejectedSceneCannotBeRescuedByVisualDepth() {
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(
                new CombinedAnalysis(false,true,CombinedFixtures.overlapAssessment().visualDepthEstimate()));
        assertEquals("INSUFFICIENT_EVIDENCE",service.estimate(image,10,20).status());verifyNoInteractions(reports);assertOneCombinedCall();
    }

    @Test void automaticRankedReferenceOneCombinedCallAndDeterministicJavaEstimatePersisted() {
        var r=service.estimate(image,10,20);
        assertEquals("ESTIMATED",r.status());assertEquals("1",r.mapillaryReference().imageId());
        assertEquals(28,r.approxDepthCm());assertEquals(28,r.representativeDepthCm());
        assertEquals("MEDIUM",r.confidence());assertEquals("MODERATE",r.riskLevel().name());
        assertEquals("POSSIBLE_SCENE_MATCH",r.sceneMatch().sceneState());assertNull(r.sceneMatch().confidence());
        assertEquals(42,r.reportId());assertTrue(r.sceneMatch().safeForDepthComparison());
        verify(mapillary).discoverReferences(10,20);verify(mapillary,never()).getReferenceImage("2");assertOneCombinedCall();
        var captor=org.mockito.ArgumentCaptor.forClass(FloodReport.class);verify(reports).saveFloodReport(captor.capture());
        assertEquals(28,captor.getValue().getEstimatedDepthCm());assertNull(captor.getValue().getSceneMatchConfidence());
        assertTrue(captor.getValue().getDepthLimitationsJson().contains(VisualDepthEstimator.NOTICE));
    }
    @Test void noCandidatesMeansNoAiNoEstimateNoPersistence() {
        when(mapillary.discoverReferences(10,20)).thenReturn(dataset(0)); var r=service.estimate(image,10,20);
        assertEquals("INSUFFICIENT_EVIDENCE",r.status()); assertNull(r.mapillaryReference());
        verify(gemini,never()).analyzeSceneAndDepth(any(),anyString(),any(),anyString()); verifyNoInteractions(reports);
    }

    @Test void falseSceneMatchBlocksEvenValidVisualDepth() throws Exception {
        var mapper=new ObjectMapper();var n=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(CombinedFixtures.overlapJson());
        n.put("sceneMatch",false);
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(
                CombinedAnalysisParser.parseOverlap(new com.flooddepth.ai.AiResponseParser(mapper),n.toString()));
        assertEquals("INSUFFICIENT_EVIDENCE",service.estimate(image,10,20).status());verifyNoInteractions(reports);assertOneCombinedCall();
    }
    @Test void noUsableObjectReturnsInsufficientWithoutAdditionalCandidateOrNeighborAiCalls() {
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(new CombinedAnalysis(true,true,new VisualDepthEstimate(false,0,"LOW","Cannot reasonably estimate.",List.of())));
        var result=service.estimate(image,10,20); assertEquals("INSUFFICIENT_EVIDENCE",result.status());
        verifyNoInteractions(reports); assertOneCombinedCall();
    }

    @Test void repeatedInputCachesCombinedEvidenceButReappliesJavaPolicyAndCalculation() {
        var a=service.estimate(image,10,20);var b=service.estimate(image,10,20);
        assertEquals(a.estimatedDepthMinCm(),b.estimatedDepthMinCm());assertEquals(a.estimatedDepthMaxCm(),b.estimatedDepthMaxCm());
        assertOneCombinedCall();
    }
    @Test void differentFloodImageDoesNotReuseCombinedEvidence() throws Exception {
        service.estimate(image,10,20);
        service.estimate(new MockMultipartFile("file","other.png","image/png",alternateImage()),10,20);
        verify(gemini,times(2)).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
    }
    @Test void differentDryImageDoesNotReuseCombinedEvidence() throws Exception {
        service.estimate(image,10,20);
        when(mapillary.getReferenceImage("1")).thenReturn(new MapillaryService.ReferenceImage(alternateImage(),MediaType.IMAGE_PNG));
        service.estimate(image,10,20); verify(gemini,times(2)).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
    }
    @Test void differentModelConfigurationDoesNotReuseCombinedEvidence() {
        when(gemini.cacheConfigurationId()).thenReturn("model-a"); service.estimate(image,10,20);
        when(gemini.cacheConfigurationId()).thenReturn("model-b"); service.estimate(image,10,20);
        verify(gemini,times(2)).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
    }
    @Test void hardQuotaIsNeitherRetriedNorCachedAndPreservesMetadata() {
        var rate=new GeminiRateLimit(GeminiRateLimit.Type.HARD_QUOTA,null,79030000L);
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenThrow(new GeminiFailureException(
                GeminiFailureException.Code.GEMINI_QUOTA_EXHAUSTED,429,"RESOURCE_EXHAUSTED",10,true,rate));
        for(int i=0;i<2;i++) {
            var error=assertThrows(GeminiFailureException.class,()->service.estimate(image,10,20));
            assertEquals(429,error.providerHttpStatus());assertEquals(rate,error.rateLimit());
        }
        verify(gemini,times(2)).analyzeSceneAndDepth(any(),anyString(),any(),anyString()); verifyNoInteractions(reports);
    }
    @ParameterizedTest @ValueSource(strings={"GEMINI_MALFORMED_RESPONSE","GEMINI_RESPONSE_VALIDATION_FAILED","GEMINI_TIMEOUT"})
    void parserValidationAndTransportFailuresNotCachedOrMisreportedAsVisualInsufficiency(String code) {
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenThrow(new GeminiFailureException(GeminiFailureException.Code.valueOf(code)));
        for(int i=0;i<2;i++) assertThrows(GeminiFailureException.class,()->service.estimate(image,10,20));
        verify(gemini,times(2)).analyzeSceneAndDepth(any(),anyString(),any(),anyString()); verifyNoInteractions(reports);
    }
    @Test void retry503UsesSameCombinedOperationAndNeverLegacyCalls() {
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenThrow(new GeminiFailureException(
                GeminiFailureException.Code.GEMINI_PROVIDER_5XX,503,"UNAVAILABLE",10,true)).thenReturn(CombinedFixtures.overlapAssessment());
        assertEquals("ESTIMATED",service.estimate(image,10,20).status());
        verify(gemini,times(2)).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
    }
    @ParameterizedTest @ValueSource(doubles={-91,91,Double.NaN,Double.POSITIVE_INFINITY})
    void invalidLatitudeFailsBeforeProviders(double latitude) {
        assertThrows(IllegalArgumentException.class,()->service.estimate(image,latitude,20)); verifyNoInteractions(mapillary,reports);
        verify(gemini,never()).analyzeSceneAndDepth(any(),anyString(),any(),anyString());
    }
    @ParameterizedTest @ValueSource(doubles={-181,181,Double.NaN,Double.NEGATIVE_INFINITY})
    void invalidLongitudeFailsBeforeProviders(double longitude) { assertThrows(IllegalArgumentException.class,()->service.estimate(image,10,longitude)); verifyNoInteractions(mapillary,reports); }
    @Test void invalidEmptyUndecodableAndUnsupportedFilesNeverReachProviders() {
        for (var f: List.of(new MockMultipartFile("file","a.png","image/png",new byte[0]),new MockMultipartFile("file","a.png","image/png",new byte[]{1,2}),new MockMultipartFile("file","a.gif","image/gif",SceneTestFixtures.png())))
            assertThrows(IllegalArgumentException.class,()->service.estimate(f,10,20));
        verifyNoInteractions(mapillary,reports);
    }
    @Test void mapillaryFailureDoesNotFallBackToAnotherProviderOrMockImage() {
        when(mapillary.discoverReferences(10,20)).thenThrow(new MapillaryException(HttpStatus.BAD_GATEWAY,"safe"));
        assertThrows(MapillaryException.class,()->service.estimate(image,10,20));
        verify(gemini,never()).analyzeSceneAndDepth(any(),anyString(),any(),anyString()); verifyNoInteractions(reports);
    }
    @Test void disabledGeminiFailsBeforeMapillary() {
        when(provider.getIfAvailable()).thenReturn(null); assertThrows(AiConfigurationException.class,()->service.estimate(image,10,20)); verifyNoInteractions(mapillary,reports);
    }
    private static byte[] alternateImage() throws Exception {
        var output=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(80,80,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",output);
        return output.toByteArray();
    }
    @Test void rejectedSceneLogsEveryObjectWithoutRunningTheCalculatorOrAnotherProviderCall() {
        when(gemini.analyzeSceneAndDepth(any(),anyString(),any(),anyString())).thenReturn(
                new CombinedAnalysis(false,true,
                        CombinedFixtures.overlapAssessment().visualDepthEstimate()));
        try(var log=new DepthDiagnosticCapture()) {
            assertEquals("INSUFFICIENT_EVIDENCE",service.estimate(image,10,20).status());
            assertTrue(log.entries("DEPTH_OBJECT").isEmpty());
            assertFalse(log.entries("DEPTH_SCENE_EVIDENCE").get(0).path("SCENE_MATCH").asBoolean());
            assertFalse(log.entries("DEPTH_SCENE_EVIDENCE").get(0).path("JAVA_ACCEPTED").asBoolean());
        }
        verifyNoInteractions(reports);assertOneCombinedCall();
    }
    @Test void cachedValidatedEvidenceIsDiagnosedOnEveryRequestWithoutAnExtraProviderCall() {
        try(var log=new DepthDiagnosticCapture()) {
            service.estimate(image,10,20);service.estimate(image,10,20);
            assertTrue(log.entries("DEPTH_OBJECT").isEmpty());assertEquals(2,log.entries("DEPTH_SCENE_EVIDENCE").size());
            assertTrue(log.entries("DEPTH_SCENE_EVIDENCE").stream().allMatch(n->n.path("JAVA_ACCEPTED").asBoolean()));
        }
        assertOneCombinedCall();
    }

}
