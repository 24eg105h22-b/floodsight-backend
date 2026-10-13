package com.flooddepth.service;

import com.flooddepth.entity.FloodReport;
import com.flooddepth.entity.RiskLevel;
import com.flooddepth.repository.FloodReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FloodReportServiceTest {

    @Mock
    private FloodReportRepository floodReportRepository;

    @Test
    void createsGpsReportWithBackendGeneratedObservedAt() {
        FloodReportService service = new FloodReportService(floodReportRepository);
        FloodReport request = new FloodReport();
        request.setLatitude(17.385);
        request.setLongitude(78.4867);
        request.setImagePath("uploads/sample.jpg");
        request.setLocation("Hyderabad");
        request.setReferenceObjectType("ROAD_DIVIDER");
        request.setReferenceHeightCm(60.0);
        request.setEstimatedDepthCm(42.0);
        request.setRiskLevel(RiskLevel.HIGH);
        request.setObservedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        when(floodReportRepository.save(any(FloodReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocalDateTime beforeSave = LocalDateTime.now();
        FloodReport saved = service.saveFloodReport(request);
        LocalDateTime afterSave = LocalDateTime.now();

        assertEquals(17.385, saved.getLatitude());
        assertEquals(78.4867, saved.getLongitude());
        assertTrue(!saved.getObservedAt().isBefore(beforeSave));
        assertTrue(!saved.getObservedAt().isAfter(afterSave));
        assertNotEquals(LocalDateTime.of(2000, 1, 1, 0, 0), saved.getObservedAt());
        verify(floodReportRepository).save(request);
    }
}