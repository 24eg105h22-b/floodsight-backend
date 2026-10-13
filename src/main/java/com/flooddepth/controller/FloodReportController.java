package com.flooddepth.controller;

import com.flooddepth.dto.FloodReportRequest;
import com.flooddepth.entity.FloodReport;
import com.flooddepth.service.FloodReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/flood-reports")
public class FloodReportController {

    private final FloodReportService floodReportService;

    public FloodReportController(FloodReportService floodReportService) {
        this.floodReportService = floodReportService;
    }

    @PostMapping
    public ResponseEntity<FloodReport> createFloodReport(
            @Valid @RequestBody FloodReportRequest request) {
        FloodReport floodReport = new FloodReport();
        floodReport.setImagePath(request.getImagePath());
        floodReport.setLatitude(request.getLatitude());
        floodReport.setLongitude(request.getLongitude());
        floodReport.setLocation(request.getLocation());
        floodReport.setReferenceHeightCm(request.getReferenceHeightCm());
        floodReport.setReferenceObjectType(request.getReferenceObjectType());
        floodReport.setEstimatedDepthCm(request.getEstimatedDepthCm());
        floodReport.setRiskLevel(request.getRiskLevel());
        FloodReport savedReport = floodReportService.saveFloodReport(floodReport);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedReport);
    }

    @GetMapping
    public List<FloodReport> getAllFloodReports() {
        return floodReportService.getAllFloodReports();
    }

    @GetMapping("/{id}")
    public ResponseEntity<FloodReport> getFloodReportById(@PathVariable Long id) {
        return floodReportService.getFloodReportById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFloodReportById(@PathVariable Long id) {
        if (!floodReportService.deleteFloodReportById(id)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.noContent().build();
    }
}
