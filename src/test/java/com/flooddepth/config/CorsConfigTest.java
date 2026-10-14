package com.flooddepth.config;

import com.flooddepth.ai.GeminiFloodVisionClient;
import com.flooddepth.controller.*;
import com.flooddepth.depth.DepthWorkflowService;
import com.flooddepth.mapillary.MapillaryService;
import com.flooddepth.saferoute.SaferRouteService;
import com.flooddepth.service.FloodReportService;
import com.flooddepth.service.ImageStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TestController.class, DepthWorkflowController.class, MapillaryController.class,
        FloodReportController.class, SaferRouteController.class, GeminiDiagnosticController.class},
        properties = "gemini.diagnostics.enabled=true")
@ActiveProfiles("production")
class CorsConfigTest {
    private static final String PRODUCTION_ORIGIN = "https://floodsight-frontend.vercel.app";

    @Autowired MockMvc mvc;
    @MockitoBean DepthWorkflowService workflow;
    @MockitoBean MapillaryService mapillary;
    @MockitoBean FloodReportService reports;
    @MockitoBean SaferRouteService routes;
    @MockitoBean GeminiFloodVisionClient gemini;
    @MockitoBean ImageStorageService storage;

    @AfterEach
    void corsChecksDoNotInvokeApplicationServices() {
        verifyNoInteractions(workflow, mapillary, reports, routes, gemini, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173", PRODUCTION_ORIGIN})
    void allowedOriginsCanReadApiResponses(String origin) throws Exception {
        mvc.perform(get("/api/test").header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(content().string("Flood Depth Backend is Running"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:5173", PRODUCTION_ORIGIN})
    void preflightAllowsExistingApiMethodsAndRequestHeaders(String origin) throws Exception {
        String[][] endpoints = {
                {"/api/test", "GET"}, {"/api/test", "HEAD"}, {"/api/test", "OPTIONS"},
                {"/api/flood/estimate-depth", "POST"}, {"/api/routes/safer", "POST"},
                {"/api/flood-reports", "GET"}, {"/api/flood-reports", "POST"},
                {"/api/flood-reports/1", "GET"}, {"/api/flood-reports/1", "DELETE"},
                {"/api/mapillary/reference", "GET"}, {"/api/mapillary/references", "GET"},
                {"/api/mapillary/reference/123/image", "GET"},
                {"/api/mapillary/reference/123/metadata", "GET"},
                {"/api/mapillary/discovery", "GET"}, {"/api/mapillary/discovery-audit", "GET"}
        };
        for (String[] endpoint : endpoints) {
            mvc.perform(options(endpoint[0]).header(HttpHeaders.ORIGIN, origin)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, endpoint[1])
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, Accept"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString(endpoint[1])))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Content-Type")))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Accept")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.invalid", "http://floodsight-frontend.vercel.app",
            "https://floodsight-frontend.vercel.app.attacker.invalid"})
    void otherOriginsReceiveNoCorsGrant(String origin) throws Exception {
        mvc.perform(get("/api/test").header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(options("/api/routes/safer").header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void unneededMethodsAndHeadersAreRejected() throws Exception {
        mvc.perform(options("/api/flood-reports").header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(options("/api/routes/safer").header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Unneeded-Header"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void localOnlyDiagnosticsDoNotReceiveBrowserCorsGrants() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173", PRODUCTION_ORIGIN}) {
            for (String path : new String[]{"/api/gemini/diagnostics", "/api/gemini/diagnostics/contract",
                    "/api/gemini/diagnostics/model"}) {
                mvc.perform(options(path).header(HttpHeaders.ORIGIN, origin)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, path.endsWith("/model") ? "GET" : "POST"))
                        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
            }
        }
    }
}
