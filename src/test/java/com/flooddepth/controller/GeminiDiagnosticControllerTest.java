package com.flooddepth.controller;

import com.flooddepth.ai.GeminiFloodVisionClient;
import com.flooddepth.service.ImageStorageService;
import com.flooddepth.exception.AiConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GeminiDiagnosticController.class, properties = "gemini.diagnostics.enabled=true")
class GeminiDiagnosticControllerTest {
    @Test void exactModelMetadataEndpointIsLocalAndDoesNotGenerate() throws Exception {
        when(client.modelMetadata()).thenReturn(new GeminiFloodVisionClient.ModelMetadataResult(true,"gemini-3.8-flash",true,
                java.util.List.of("generateContent"),true,200,10,null,null));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/gemini/diagnostics/model"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generateContentSupported").value(true));
        verify(client).modelMetadata(); verifyNoMoreInteractions(client);
    }
    @Test void newContractEndpointsDenyRemoteAndRejectImagesForTextBeforeAnyCall() throws Exception {
        mvc.perform(post("/api/gemini/diagnostics/contract").with(request->{request.setRemoteAddr("192.0.2.2");return request;})
                .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"TEXT_JSON\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/gemini/diagnostics/contract").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"COMBINED_SCHEMA\",\"imagePath\":\"uploads/flood.png\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client,storage);
    }
    @Autowired MockMvc mvc;
    @MockitoBean GeminiFloodVisionClient client;
    @MockitoBean ImageStorageService storage;

    @Test
    void publicDiagnosticResultIsSafeAndProviderFailureIsNotHttpSuccess() throws Exception {
        when(client.diagnose(any(), any(), any())).thenReturn(new GeminiFloodVisionClient.DiagnosticResult(
                "TEXT", false, "gemini-2.5-flash", true, true, 429, true, 12, false, false, false,
                "GEMINI_RATE_LIMIT", "RESOURCE_EXHAUSTED", "Gemini quota or rate limit was reached.", null));
        mvc.perform(post("/api/gemini/diagnostics").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"TEXT\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.providerHttpStatus").value(429))
                .andExpect(jsonPath("$.diagnosticCode").value("GEMINI_RATE_LIMIT"))
                .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.rawResponse").doesNotExist());
    }

    @Test
    void deniesRemoteRequestsEvenWithSpoofedForwardedHeaders() throws Exception {
        mvc.perform(post("/api/gemini/diagnostics").with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })
                .header("X-Forwarded-For", "127.0.0.1").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"TEXT\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(client, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"stage\":\"ARBITRARY\"}", "{\"stage\":\"TEXT\",\"imagePath\":\"uploads/a.jpg\"}"})
    void invalidStagesNeverContactProvider(String body) throws Exception {
        mvc.perform(post("/api/gemini/diagnostics").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(client, storage);
    }

    @Test
    void invalidConfigurationIsSanitized() throws Exception {
        when(client.diagnose(any(), any(), any())).thenThrow(new AiConfigurationException("private configuration"));
        mvc.perform(post("/api/gemini/diagnostics").contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"TEXT\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private configuration"))));
    }

    @Test
    void invalidImageFailsBeforeProviderCall() throws Exception {
        when(storage.loadStoredImage("uploads/bad.jpg")).thenThrow(new IllegalArgumentException("Image missing"));
        mvc.perform(post("/api/gemini/diagnostics").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"IMAGE\",\"imagePath\":\"uploads/bad.jpg\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }
}
