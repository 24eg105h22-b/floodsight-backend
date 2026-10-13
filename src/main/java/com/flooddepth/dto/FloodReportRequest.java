package com.flooddepth.dto;

import com.flooddepth.entity.RiskLevel;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FloodReportRequest {

    @NotBlank(message = "imagePath must not be blank")
    private String imagePath;

    @NotNull(message = "latitude is required")
    @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "latitude must be between -90 and 90")
    private Double latitude;

    @NotNull(message = "longitude is required")
    @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "longitude must be between -180 and 180")
    private Double longitude;

    @NotBlank(message = "location must not be blank")
    private String location;

    private Double referenceHeightCm;

    @NotBlank(message = "referenceObjectType must not be blank")
    private String referenceObjectType;

    @NotNull(message = "estimatedDepthCm is required")
    @PositiveOrZero(message = "estimatedDepthCm must be zero or greater")
    private Double estimatedDepthCm;

    @NotNull(message = "riskLevel is required")
    private RiskLevel riskLevel;

    @AssertTrue(message = "latitude and longitude must be finite numbers")
    public boolean isCoordinatePairFinite() {
        return (latitude == null || Double.isFinite(latitude))
                && (longitude == null || Double.isFinite(longitude));
    }
}