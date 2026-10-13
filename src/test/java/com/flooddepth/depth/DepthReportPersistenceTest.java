package com.flooddepth.depth;

import com.flooddepth.entity.FloodReport;
import com.flooddepth.entity.RiskLevel;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DepthReportPersistenceTest {
    @Test void compatibilityColumnHasNoAccessorAndCannotAppearInEitherJsonSerializer() throws Exception {
        var report = new FloodReport(); var retained=FloodReport.class.getDeclaredField("unusedLegacyImageOrigin");
        retained.setAccessible(true); retained.set(report,true);
        assertEquals("synthetic",retained.getAnnotation(jakarta.persistence.Column.class).name());
        for(var method:FloodReport.class.getMethods()) assertFalse(method.getName().matches("(?i).*(synthetic|unusedLegacyImageOrigin).*"));
        var jackson2=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report);
        var jackson3=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(report);
        for(String json:new String[]{jackson2,jackson3}) {
            assertFalse(json.contains("synthetic")); assertFalse(json.contains("unusedLegacyImageOrigin"));
        }
    }
    @Test void additiveFieldsRoundTripAndLegacyReportsStayCompatible() {
        try(var factory=new Configuration().addAnnotatedClass(FloodReport.class)
                .setProperty("hibernate.connection.driver_class","org.h2.Driver")
                .setProperty("hibernate.connection.url","jdbc:h2:mem:depth_fields;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto","create-drop").setProperty("hibernate.show_sql","false").buildSessionFactory()) {
            Long currentId,legacyId;
            try(var session=factory.openSession()) {
                var transaction=session.beginTransaction(); var current=new FloodReport();
                current.setLatitude(10d); current.setLongitude(20d); current.setEstimatedDepthCm(7.5); current.setDepthMinCm(3d); current.setDepthMaxCm(12d);
                current.setDepthConfidence("LOW"); current.setRiskLevel(RiskLevel.LOW); current.setMapillaryImageId("123");
                current.setDepthEvidenceJson("[]"); current.setDepthLimitationsJson("[\"Unmeasured\"]"); current.setSceneMatchDecision("LIKELY_SAME_SCENE");
                current.setSceneMatchConfidence(.94); current.setRiskRangeCrossesThreshold(false); session.persist(current);
                var legacy=new FloodReport(); legacy.setEstimatedDepthCm(5d); session.persist(legacy); transaction.commit(); currentId=current.getId(); legacyId=legacy.getId();
            }
            try(var session=factory.openSession()) {
                var saved=session.find(FloodReport.class,currentId); assertEquals(3,saved.getDepthMinCm()); assertEquals(12,saved.getDepthMaxCm());
                assertEquals("123",saved.getMapillaryImageId()); assertNotNull(saved.getObservedAt()); assertEquals("[]",saved.getDepthEvidenceJson());
                var legacy=session.find(FloodReport.class,legacyId); assertNull(legacy.getDepthMinCm()); assertEquals(5,legacy.getEstimatedDepthCm());
            }
        }
    }
}
