package com.flooddepth.controller;

import com.flooddepth.mapillary.MapillaryException;
import com.flooddepth.mapillary.MapillaryReference;
import com.flooddepth.mapillary.MapillaryReferences;
import com.flooddepth.mapillary.MapillaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MapillaryController.class)
class MapillaryControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private MapillaryService service;

    @Test
    void returnsReferenceMetadataAndBackendImagePath() throws Exception {
        when(service.findReference(10, 20)).thenReturn(new MapillaryReference("123",
                "/api/mapillary/reference/123/image", Instant.parse("2024-01-01T00:00:00Z"),
                10, 20, 0, "Mapillary", "computed_geometry", "perspective", 45.0, "contributor",
                "https://www.mapillary.com/app/?pKey=123", 10, 20, 100, 2, 1, false,
                "Proximity band, exact distance", "Scene match unverified", null,
                "STRONG_PROXIMITY", "UNVERIFIED_SCENE", 1, List.of("Exact distance: 0"),
                "ACCESSIBLE_STRUCTURALLY_VALID"));
        mvc.perform(get("/api/mapillary/reference?lat=10&lon=20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageId").value("123"))
                .andExpect(jsonPath("$.imageUrl").value("/api/mapillary/reference/123/image"))
                .andExpect(jsonPath("$.source").value("Mapillary"))
                .andExpect(jsonPath("$.capturedAt").value("2024-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.sceneState").value("UNVERIFIED_SCENE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?lat=10", "?lon=20", "?lat=no&lon=20", "?lat=NaN&lon=20",
            "?lat=91&lon=20", "?lat=10&lon=-181", "?lat=Infinity&lon=20"})
    void handlesMissingMalformedAndOutOfRangeCoordinates(String query) throws Exception {
        mvc.perform(get("/api/mapillary/reference" + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 502, 503, 504})
    void mapsServiceErrorsToMeaningfulHttpStatuses(int status) throws Exception {
        when(service.findReference(10, 20)).thenThrow(new MapillaryException(HttpStatus.valueOf(status), "safe error"));
        mvc.perform(get("/api/mapillary/reference?lat=10&lon=20"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error").value("safe error"));
    }

    @Test
    void servesImageBytesThroughBackend() throws Exception {
        when(service.getReferenceImage("123"))
                .thenReturn(new MapillaryService.ReferenceImage(new byte[] {1, 2, 3}, MediaType.IMAGE_JPEG));
        mvc.perform(get("/api/mapillary/reference/123/image"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[] {1, 2, 3})).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void topCandidatesEndpointReturnsAuditDataAndEmptyListWithoutChangingSingleContract() throws Exception {
        when(service.findReferences(10, 20)).thenReturn(new MapillaryReferences(List.of(), 10, 20, 250,
                0, 0, 0, 0, 0, false, "dataset", "screening", "Local rank", "Scene unverified", List.of()));
        mvc.perform(get("/api/mapillary/references?lat=10&lon=20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.requestedLatitude").value(10))
                .andExpect(jsonPath("$.searchRadiusMeters").value(250))
                .andExpect(jsonPath("$.datasetFingerprint").value("dataset"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?lat=10", "?lat=NaN&lon=20", "?lat=10&lon=181"})
    void validatesCoordinatesForTopCandidatesEndpoint(String query) throws Exception {
        mvc.perform(get("/api/mapillary/references" + query)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
