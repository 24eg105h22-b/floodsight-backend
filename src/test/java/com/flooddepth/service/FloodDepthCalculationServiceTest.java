package com.flooddepth.service;

import com.flooddepth.entity.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FloodDepthCalculationServiceTest {
    final FloodDepthCalculationService risk = new FloodDepthCalculationService();
    @ParameterizedTest @CsvSource({"0,LOW", "15,LOW", "15.01,MODERATE", "30,MODERATE", "30.01,HIGH", "60,HIGH", "60.01,SEVERE"})
    void existingRiskBoundariesRemainDeterministic(double cm, RiskLevel expected) {
        assertEquals(expected, risk.determineRiskLevel(cm));
    }
    @ParameterizedTest @ValueSource(doubles={-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
    void invalidDepthCannotBecomeRisk(double cm) {
        assertThrows(IllegalArgumentException.class, () -> risk.determineRiskLevel(cm));
    }
}
