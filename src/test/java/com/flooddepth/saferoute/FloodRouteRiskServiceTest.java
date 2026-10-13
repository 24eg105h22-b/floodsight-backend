package com.flooddepth.saferoute;

import com.flooddepth.entity.*;
import com.flooddepth.repository.FloodReportRepository;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class FloodRouteRiskServiceTest {
    final Instant now=Instant.parse("2026-10-11T00:00:00Z");
    final FloodReportRepository repository=mock(FloodReportRepository.class);
    final FloodRouteRiskService service=new FloodRouteRiskService(repository,25,ZoneOffset.UTC,Set.of(6,7,8,9,10),Clock.fixed(now,ZoneOffset.UTC));
    final List<Point> path=List.of(new Point(0,0),new Point(0,.02));
    final Candidate candidate=new Candidate("route-1",2200,300,path,RouteGeometry.encode(path));
    static FloodReport report(long id,double lat,double lon,LocalDateTime time){var r=new FloodReport();r.setId(id);r.setLatitude(lat);r.setLongitude(lon);r.setObservedAt(time);r.setEstimatedDepthCm(65d);r.setDepthMinCm(60d);r.setDepthMaxCm(70d);r.setDepthConfidence("HIGH");r.setRiskLevel(RiskLevel.SEVERE);return r;}
    FloodReport recent(long id,double lat,double lon){return report(id,lat,lon,LocalDateTime.ofInstant(now.minusSeconds(3600),ZoneOffset.UTC));}
    void rows(List<FloodReport> reports){when(repository.findRouteArea(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any(),any())).thenReturn(reports);}
    @BeforeEach void setup(){rows(List.of());}
    @Test void detectsReportAtRouteMiddleAndIgnoresDistantPoint(){rows(List.of(recent(1,.0001,.01),recent(2,.001,.01)));var result=service.assess(List.of(candidate)).routes().get(0);assertEquals(1,result.floodEvidenceCount());assertEquals(1,result.recentFloodEvidenceCount());assertEquals(0,result.historicalFloodEvidenceCount());assertTrue(result.riskScore()>50);assertEquals(Risk.HIGH,result.riskLevel());assertEquals(AssessmentState.ASSESSED,result.assessmentState());verify(repository).findRouteArea(anyDouble(),anyDouble(),anyDouble(),anyDouble(),any(),argThat(p->p.getPageSize()==10001));}
    @Test void recencyPolicySeparatesRecentAndHistoricalAndComparableRainyMonths(){assertEquals(1,service.recency(now.minusSeconds(3600),now));assertEquals(.6,service.recency(now.minusSeconds(12*3600),now));assertEquals(.3,service.recency(now.minusSeconds(3*86400),now));assertEquals(.18,service.recency(Instant.parse("2026-09-01T00:00:00Z"),now));assertEquals(.08,service.recency(Instant.parse("2026-02-01T00:00:00Z"),now));assertEquals(.09,service.recency(Instant.parse("2025-09-01T00:00:00Z"),now));}
    @Test void oldEvidenceIsNeverDescribedAsCurrentFlooding(){rows(List.of(report(1,0,.01,LocalDateTime.parse("2026-09-01T00:00:00"))));var route=service.assess(List.of(candidate)).routes().get(0);assertEquals(0,route.recentFloodEvidenceCount());assertEquals(1,route.historicalFloodEvidenceCount());assertTrue(route.summary().contains("Historically"));assertFalse(route.summary().contains("currently flooded"));assertTrue(route.riskScore()<20);}
    @Test void lowConfidenceAndGreaterDistanceReduceRisk(){var high=recent(1,0,.01);rows(List.of(high));double first=service.assess(List.of(candidate)).routes().get(0).riskScore();high.setDepthConfidence("LOW");high.setLatitude(.0002);double second=service.assess(List.of(candidate)).routes().get(0).riskScore();assertTrue(second<first);}
    @Test void failedInsufficientFutureAndInvalidNumericReportsAreExcluded(){var nullDepth=recent(1,0,.01);nullDepth.setEstimatedDepthCm(null);var insufficient=recent(2,0,.01);insufficient.setDepthConfidence("INSUFFICIENT");var future=report(3,0,.01,LocalDateTime.ofInstant(now.plusSeconds(1),ZoneOffset.UTC));var bad=recent(4,0,.01);bad.setDepthMaxCm(Double.NaN);rows(List.of(nullDepth,insufficient,future,bad));assertEquals(0,service.assess(List.of(candidate)).routes().get(0).floodEvidenceCount());}
    @Test void repeatedUploadsOnSameDayDoNotExplodeScoreButDifferentDaysCorroborateWithCap(){rows(List.of(recent(1,0,.01)));double one=service.assess(List.of(candidate)).routes().get(0).riskScore();rows(java.util.stream.LongStream.rangeClosed(1,100).mapToObj(i->recent(i,0,.01)).toList());assertEquals(one,service.assess(List.of(candidate)).routes().get(0).riskScore());rows(List.of(recent(1,0,.01),report(2,0,.01,LocalDateTime.ofInstant(now.minusSeconds(2*86400),ZoneOffset.UTC))));double repeated=service.assess(List.of(candidate)).routes().get(0).riskScore();assertTrue(repeated>one);assertTrue(repeated<80);}
    @Test void reportDuplicatesAcrossRoutePrefiltersAreCountedOnce(){rows(List.of(recent(1,0,.01)));var two=new Candidate("route-2",2300,320,path,RouteGeometry.encode(path));var result=service.assess(List.of(candidate,two));assertTrue(result.routes().stream().allMatch(r->r.floodEvidenceCount()==1));}
    @Test void riskClassesAndEmptyCoverageAreExplicit(){assertEquals(Risk.LOW,FloodRouteRiskService.risk(0));assertEquals(Risk.MODERATE,FloodRouteRiskService.risk(20));assertEquals(Risk.HIGH,FloodRouteRiskService.risk(50));assertEquals(Risk.SEVERE,FloodRouteRiskService.risk(80));var route=service.assess(List.of(candidate)).routes().get(0);assertEquals(0,route.riskScore());assertEquals(0,route.floodEvidenceCount());assertNull(route.riskLevel());assertEquals(AssessmentState.LIMITED_EVIDENCE,route.assessmentState());assertTrue(route.summary().contains("No significant"));}
    @Test void radiusConfigurationCannotSilentlyBecomeHuge(){assertThrows(IllegalArgumentException.class,()->new FloodRouteRiskService(repository,500,ZoneOffset.UTC,Set.of(6),Clock.systemUTC()));}
}
