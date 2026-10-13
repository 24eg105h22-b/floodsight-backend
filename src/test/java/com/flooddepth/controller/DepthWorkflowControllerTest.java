package com.flooddepth.controller;

import com.flooddepth.depth.*;
import com.flooddepth.exception.GeminiFailureException;
import com.flooddepth.mapillary.MapillaryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.HttpStatus;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DepthWorkflowController.class)
class DepthWorkflowControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DepthWorkflowService workflow;
    final MockMultipartFile file=new MockMultipartFile("file","photo.png","image/png",new byte[]{1,2,3});
    @ParameterizedTest @ValueSource(strings={"http://localhost:5173","http://127.0.0.1:5173"})
    void bothLocalWebsiteOriginsReachWorkflowWithJsonResponse(String origin) throws Exception {
        when(workflow.estimate(any(),anyDouble(),anyDouble())).thenReturn(new DepthResult("INSUFFICIENT_EVIDENCE",10,20,
                null,null,null,null,null,"INSUFFICIENT",null,false,List.of(),List.of("Insufficient landmarks"),null));
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).header("Origin",origin)
                        .param("latitude","10").param("longitude","20"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",origin))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"));
        verify(workflow).estimate(any(),eq(10d),eq(20d));
        mvc.perform(options("/api/flood/estimate-depth").header("Origin",origin).header("Access-Control-Request-Method","POST"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",origin));
    }
    @Test void unrelatedOriginsRemainRejectedWithJsonAndNeverInvokeWorkflow() throws Exception {
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).header("Origin","https://untrusted.invalid")
                        .param("latitude","10").param("longitude","20"))
                .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("ERROR")).andExpect(jsonPath("$.code").value("REQUEST_NOT_ALLOWED"));
        verifyNoInteractions(workflow);
    }
    @Test void acceptsOneImageAndCoordinatesWithNoReferenceSelectionInput() throws Exception {
        when(workflow.estimate(any(),eq(10d),eq(20d))).thenReturn(new DepthResult("INSUFFICIENT_EVIDENCE",10,20,null,null,null,null,null,"INSUFFICIENT",null,false,List.of(),List.of("No evidence"),null));
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude","20"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.synthetic").doesNotExist()).andExpect(jsonPath("$.controlledSynthetic").doesNotExist())
                .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(jsonPath("$.riskLevel").isEmpty());
        verify(workflow).estimate(any(),eq(10d),eq(20d));
    }
    @Test void estimatedResultSerializesNestedRecordsInstantEnumsAndNullableFields() throws Exception {
        var reference = new DepthResult.Reference("123",java.time.Instant.parse("2023-01-01T00:00:00Z"),10,20,5,
                "/api/mapillary/reference/123/image",null,null);
        var scene = new DepthResult.SceneMatch(com.flooddepth.scene.SceneAssessment.MatchDecision.LIKELY_SAME_SCENE,
                .95,"POSSIBLE_SCENE_MATCH",true,List.of());
        var evidence = new DepthResult.Evidence("kerb","KERB","Kerb",10,20,.3,.6,3,12,"Waterline", "Legacy report",List.of());
        when(workflow.estimate(any(),anyDouble(),anyDouble())).thenReturn(new DepthResult("ESTIMATED",10,20,
                reference,scene,3d,12d,7.5,"LOW",com.flooddepth.entity.RiskLevel.LOW,false,List.of(evidence),List.of("Unmeasured height"),1L));
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude","20"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("ESTIMATED"))
                .andExpect(jsonPath("$.synthetic").doesNotExist())
                .andExpect(jsonPath("$.mapillaryReference.capturedAt").value("2023-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.referenceEvidence[0].type").value("KERB"))
                .andExpect(jsonPath("$.referenceEvidence[0].dimension").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private_source_marker"))))
                .andExpect(jsonPath("$.sceneMatch.decision").value("LIKELY_SAME_SCENE"))
                .andExpect(jsonPath("$.representativeDepthCm").value(7.5))
                .andExpect(jsonPath("$.approxDepthCm").value(8));
    }
    @Test void visualOnlyResponseSerializesOneApproximateValueWithNoRequiredObjects() throws Exception {
        var reference=new DepthResult.Reference("123",java.time.Instant.parse("2023-01-01T00:00:00Z"),10,20,5,
                "/api/mapillary/reference/123/image",null,null);
        var scene=new DepthResult.SceneMatch(com.flooddepth.scene.SceneAssessment.MatchDecision.LIKELY_SAME_SCENE,
                .95,"POSSIBLE_SCENE_MATCH",true,List.of());
        when(workflow.estimate(any(),anyDouble(),anyDouble())).thenReturn(new DepthResult("ESTIMATED",10,20,
                reference,scene,28.4,28.4,28.4,"MEDIUM",com.flooddepth.entity.RiskLevel.MODERATE,false,
                List.of(),List.of(VisualDepthEstimator.NOTICE),42L,List.of(),"DIRECT_VISUAL_ESTIMATE",
                new DepthResult.VisualEvidence(28.4,"MEDIUM","Road water contact provides approximate scale.",List.of())));
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude","20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approxDepthCm").value(28))
                .andExpect(jsonPath("$.visualEvidence.approxDepthCm").value(28.4))
                .andExpect(jsonPath("$.referenceEvidence").isEmpty()).andExpect(jsonPath("$.calibrationEvidence").isEmpty())
                .andExpect(jsonPath("$.confidence").value("MEDIUM")).andExpect(jsonPath("$.riskLevel").value("MODERATE"));
    }
    @Test void errorStatusAndJsonContractDistinguishProviderParserInternalAndInvalidInput() throws Exception {
        RuntimeException[] failures = {new GeminiFailureException(GeminiFailureException.Code.GEMINI_MALFORMED_RESPONSE),
                new com.flooddepth.exception.AiConfigurationException("private"),new IllegalStateException("private"),new IllegalArgumentException("private")};
        int[] statuses = {502,503,500,400};
        String[] codes = {"GEMINI_MALFORMED_RESPONSE","ANALYSIS_UNAVAILABLE","DEPTH_ANALYSIS_FAILED","INVALID_INPUT"};
        for(int i=0;i<failures.length;i++) {
            doThrow(failures[i]).when(workflow).estimate(any(),anyDouble(),anyDouble());
            mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude","20"))
                    .andExpect(status().is(statuses[i])).andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.status").value("ERROR")).andExpect(jsonPath("$.code").value(codes[i]))
                    .andExpect(jsonPath("$.message").isString())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private"))));
        }
    }
    @ParameterizedTest @ValueSource(strings={"GEMINI_RATE_LIMIT","GEMINI_RATE_LIMIT_RETRYABLE","GEMINI_QUOTA_EXHAUSTED"})
    void allRateLimitCategoriesHaveTheSameSafeUserMessage(String code) throws Exception {
        doThrow(new GeminiFailureException(GeminiFailureException.Code.valueOf(code),429,"RESOURCE_EXHAUSTED",10,true)).when(workflow).estimate(any(),anyDouble(),anyDouble());
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude","20"))
            .andExpect(status().isBadGateway()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.message").value("Flood analysis is temporarily busy. Please try again shortly."));
    }
    @ParameterizedTest @ValueSource(strings={"", "not-a-number"})
    void malformedCoordinatesNeverInvokeWorkflow(String v) throws Exception {
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude",v).param("longitude","20")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude",v)).andExpect(status().isBadRequest()); verifyNoInteractions(workflow);
    }
    @Test void missingImageOrCoordinatesAreSanitized400() throws Exception {
        mvc.perform(multipart("/api/flood/estimate-depth").param("latitude","10").param("longitude","20")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/flood/estimate-depth").file(file)).andExpect(status().isBadRequest()); verifyNoInteractions(workflow);
    }
    @Test void providerAndStorageExceptionsNeverEchoPrivateContentOrCauses() throws Exception {
        for(RuntimeException exception:new RuntimeException[]{new MapillaryException(HttpStatus.BAD_GATEWAY,"mock-private-token"),
                new GeminiFailureException(GeminiFailureException.Code.GEMINI_MALFORMED_RESPONSE),new IllegalStateException("mock-private-key"),new IllegalArgumentException("mock-private-content")}) {
            doThrow(exception).when(workflow).estimate(any(),anyDouble(),anyDouble());
            mvc.perform(multipart("/api/flood/estimate-depth").file(file).param("latitude","10").param("longitude","20"))
                    .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(result.getResponse().getStatus() >= 400))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mock-private"))))
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }
}
