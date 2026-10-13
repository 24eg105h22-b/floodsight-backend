package com.flooddepth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "flood_reports")
@Getter
@Setter
@NoArgsConstructor
public class FloodReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "location")
    private String location;

    @Column(name = "reference_height_cm")
    private Double referenceHeightCm;

    @Column(name = "reference_object_type")
    private String referenceObjectType;

    @Column(name = "estimated_depth_cm")
    private Double estimatedDepthCm;

    private Double depthMinCm;
    private Double depthMaxCm;
    private String depthConfidence;
    private String mapillaryImageId;
    private String sceneMatchDecision;
    private Double sceneMatchConfidence;
    private Boolean riskRangeCrossesThreshold;
    // Retained only to avoid a destructive schema change; no application accessor or API field.
    @Column(name = "synthetic")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private Boolean unusedLegacyImageOrigin;

    @Column(columnDefinition = "TEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String depthEvidenceJson;

    @Column(columnDefinition = "TEXT")
    private String depthLimitationsJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    @Column(name = "observed_at", updatable = false)
    private LocalDateTime observedAt;

    @PrePersist
    void setObservedAt() {
        if (observedAt == null) {
            observedAt = LocalDateTime.now();
        }
    }
}
