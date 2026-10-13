package com.flooddepth.service;

import com.flooddepth.entity.FloodReport;
import com.flooddepth.repository.FloodReportRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class FloodReportService {

    private final FloodReportRepository floodReportRepository;

    public FloodReportService(FloodReportRepository floodReportRepository) {
        this.floodReportRepository = floodReportRepository;
    }

    public FloodReport saveFloodReport(FloodReport floodReport) {
        floodReport.setId(null);
        floodReport.setObservedAt(LocalDateTime.now());
        return floodReportRepository.save(floodReport);
    }

    public List<FloodReport> getAllFloodReports() {
        return floodReportRepository.findAll();
    }

    public Optional<FloodReport> getFloodReportById(Long id) {
        return floodReportRepository.findById(id);
    }

    public boolean deleteFloodReportById(Long id) {
        if (!floodReportRepository.existsById(id)) {
            return false;
        }

        floodReportRepository.deleteById(id);
        return true;
    }
}
