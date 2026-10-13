package com.flooddepth.controller;

import com.flooddepth.entity.FloodReport;
import com.flooddepth.entity.RiskLevel;
import com.flooddepth.service.FloodReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FloodReportController.class)
class FloodReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FloodReportService floodReportService;

    @Test
    void createsFloodReport() throws Exception {
        when(floodReportService.saveFloodReport(any(FloodReport.class)))
                .thenReturn(createReport());

        mockMvc.perform(post("/api/flood-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imagePath": "uploads/sample.jpg",
                                  "latitude": 17.385,
                                  "longitude": 78.4867,
                                  "location": "Hyderabad",
                                  "referenceObjectType": "ROAD_DIVIDER",
                                  "estimatedDepthCm": 42.0,
                                  "riskLevel": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.location").value("Hyderabad"))
                .andExpect(jsonPath("$.latitude").value(17.385))
                .andExpect(jsonPath("$.longitude").value(78.4867))
                .andExpect(jsonPath("$.observedAt").value("2026-09-21T10:30:00"))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"));
    }

    @Test
    void rejectsInvalidLatitude() throws Exception {
        mockMvc.perform(post("/api/flood-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson(91, 78.4867, 42)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidLongitude() throws Exception {
        mockMvc.perform(post("/api/flood-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson(17.385, 181, 42)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativeEstimatedDepth() throws Exception {
        mockMvc.perform(post("/api/flood-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportJson(17.385, 78.4867, -1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsAllFloodReports() throws Exception {
        when(floodReportService.getAllFloodReports()).thenReturn(List.of(createReport()));

        mockMvc.perform(get("/api/flood-reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].location").value("Hyderabad"));
    }

    @Test
    void returnsFloodReportById() throws Exception {
        when(floodReportService.getFloodReportById(1L))
                .thenReturn(Optional.of(createReport()));

        mockMvc.perform(get("/api/flood-reports/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void returnsNotFoundForMissingFloodReport() throws Exception {
        when(floodReportService.getFloodReportById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/flood-reports/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesFloodReport() throws Exception {
        when(floodReportService.deleteFloodReportById(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/flood-reports/1"))
                .andExpect(status().isNoContent());

        verify(floodReportService).deleteFloodReportById(1L);
    }

    @Test
    void returnsNotFoundWhenDeletingMissingFloodReport() throws Exception {
        when(floodReportService.deleteFloodReportById(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/flood-reports/99"))
                .andExpect(status().isNotFound());
    }

    private FloodReport createReport() {
        FloodReport report = new FloodReport();
        report.setId(1L);
        report.setImagePath("uploads/sample.jpg");
        report.setLatitude(17.385);
        report.setLongitude(78.4867);
        report.setLocation("Hyderabad");
        report.setReferenceHeightCm(60.0);
        report.setReferenceObjectType("ROAD_DIVIDER");
        report.setEstimatedDepthCm(42.0);
        report.setRiskLevel(RiskLevel.HIGH);
        report.setObservedAt(LocalDateTime.of(2026, 9, 21, 10, 30));
        return report;
    }

    private String reportJson(double latitude, double longitude, double estimatedDepthCm) {
        return """
                {
                  "imagePath": "uploads/sample.jpg",
                  "latitude": %s,
                  "longitude": %s,
                  "location": "Hyderabad",
                  "referenceObjectType": "ROAD_DIVIDER",
                  "estimatedDepthCm": %s,
                  "riskLevel": "HIGH"
                }
                """.formatted(latitude, longitude, estimatedDepthCm);
    }
}
