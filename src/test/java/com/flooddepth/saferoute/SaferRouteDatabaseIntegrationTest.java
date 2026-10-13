package com.flooddepth.saferoute;

import com.flooddepth.repository.FloodReportRepository;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:routing_integration;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop","gemini.enabled=false","geoapify.api-key=","google.routes.enabled=false","google.geocoding.enabled=false"})
@Transactional
class SaferRouteDatabaseIntegrationTest {
    @Autowired FloodReportRepository repository;
    final Instant now=Instant.parse("2026-10-11T00:00:00Z");
    @Test void databasePrefilterAndFullJavaWorkflowUseValidSavedEvidenceWithoutAi(){
        var good=FloodRouteRiskServiceTest.report(1,0,.01,LocalDateTime.ofInstant(now.minusSeconds(3600),ZoneOffset.UTC));good.setId(null);repository.save(good);
        var distant=FloodRouteRiskServiceTest.report(2,50,50,good.getObservedAt());distant.setId(null);repository.save(distant);
        var failed=FloodRouteRiskServiceTest.report(3,0,.01,good.getObservedAt());failed.setId(null);failed.setEstimatedDepthCm(null);repository.save(failed);
        var insufficient=FloodRouteRiskServiceTest.report(4,0,.01,good.getObservedAt());insufficient.setId(null);insufficient.setDepthConfidence("INSUFFICIENT");repository.save(insufficient);
        var future=FloodRouteRiskServiceTest.report(5,0,.01,LocalDateTime.ofInstant(now.plusSeconds(10),ZoneOffset.UTC));future.setId(null);repository.save(future);repository.flush();
        var selected=repository.findRouteArea(-.001,.001,-.001,.021,LocalDateTime.ofInstant(now,ZoneOffset.UTC),PageRequest.of(0,10001));
        assertEquals(1,selected.size());assertEquals(good.getId(),selected.get(0).getId());
        var geo=mock(GeoapifyClient.class);var destination=new Destination("Test destination",0,.02);
        var risky=List.of(new Point(0,0),new Point(0,.02));var safer=List.of(new Point(0,0),new Point(.005,.01),new Point(0,.02));
        when(geo.geocode("Test destination",new Point(0,0))).thenReturn(destination);
        when(geo.routes(any(),any())).thenReturn(List.of(new Candidate("route-1",2200,300,risky,RouteGeometry.encode(risky)),new Candidate("route-2",2600,350,safer,RouteGeometry.encode(safer))));
        var risk=new FloodRouteRiskService(repository,25,ZoneOffset.UTC,Set.of(6,7,8,9,10),Clock.fixed(now,ZoneOffset.UTC));
        var result=new SaferRouteService(geo,risk).plan(new Request(0d,0d,"Test destination"));
        assertEquals("route-2",result.recommendedRouteId());assertEquals(1,result.routes().get(0).recentFloodEvidenceCount());assertEquals(0,result.routes().get(1).floodEvidenceCount());
        assertEquals(5,repository.count()); // Routing never creates fabricated flood observations.
    }
}
