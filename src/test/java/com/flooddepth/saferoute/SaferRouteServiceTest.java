package com.flooddepth.saferoute;

import com.flooddepth.saferoute.SaferRouteModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SaferRouteServiceTest {
    final GeoapifyClient client=mock(GeoapifyClient.class);final FloodRouteRiskService risk=mock(FloodRouteRiskService.class);
    final SaferRouteService service=new SaferRouteService(client,risk);
    static AssessedRoute route(String id,double distance,double time,double score){return new AssessedRoute(id,distance,time,FloodRouteRiskService.risk(score),score,0,0,0,List.of(new Point(0,0),new Point(0,.01)),"??", "No evidence",List.of());}
    @Test void saferReasonablyLongerRouteWinsMeaningfulRiskDifference(){assertEquals("route-2",SaferRouteService.recommend(List.of(route("route-1",8000,1320,70),route("route-2",9100,1560,5))).id());}
    @Test void nearlyEqualRiskFavoursShorterFasterRoute(){assertEquals("route-1",SaferRouteService.recommend(List.of(route("route-1",8000,1320,5),route("route-2",9100,1560,4))).id());}
    @Test void absurdDetourForTinyDifferenceIsNotRecommended(){assertEquals("route-1",SaferRouteService.recommend(List.of(route("route-1",8000,1320,5),route("route-2",40000,6000,0))).id());}
    @Test void equalRiskAndTimeUsesDistanceThenStableId(){assertEquals("route-2",SaferRouteService.recommend(List.of(route("route-1",9000,1320,0),route("route-2",8000,1320,0))).id());assertEquals("route-1",SaferRouteService.recommend(List.of(route("route-2",8000,1320,0),route("route-1",8000,1320,0))).id());}
    @ParameterizedTest @ValueSource(doubles={-91,91,Double.NaN,Double.POSITIVE_INFINITY})
    void invalidLatitudeStopsBeforeProvider(double value){assertEquals(RoutePlanningException.Code.INVALID_START,assertThrows(RoutePlanningException.class,()->service.plan(new Request(value,20d,"place"))).code());verifyNoInteractions(client,risk);}
    @ParameterizedTest @ValueSource(doubles={-181,181,Double.NaN})
    void invalidLongitudeStopsBeforeProvider(double value){assertThrows(RoutePlanningException.class,()->service.plan(new Request(10d,value,"place")));verifyNoInteractions(client,risk);}
    @Test void missingAndBlankInputsNeverMakeExternalCalls(){for(Request request:java.util.Arrays.asList(null,new Request(null,20d,"place"),new Request(10d,20d," "),new Request(10d,20d,"place\nsecret")))assertThrows(RoutePlanningException.class,()->service.plan(request));verifyNoInteractions(client,risk);}
    @Test void fullWorkflowWithoutAiAndNoEvidenceUsesEfficiencyAndHonestLimitations(){
        var destination=new Destination("Test place",0,.01);var candidate=new Candidate("route-1",1000,60,List.of(new Point(0,0),new Point(0,.01)),"??");
        when(client.geocode("Test place",new Point(0,0))).thenReturn(destination);when(client.routes(any(),any())).thenReturn(List.of(candidate));
        when(risk.assess(any())).thenReturn(new FloodRouteRiskService.Assessment(List.of(route("route-1",1000,60,0)),false));
        var response=service.plan(new Request(0d,0d," Test place "));assertEquals("route-1",response.recommendedRouteId());assertTrue(response.limitations().contains("No significant flood evidence was found along the compared routes."));assertTrue(response.limitations().contains("Limited flood reports may reduce coverage."));assertTrue(response.limitations().stream().anyMatch(s->s.contains("Only one distinct")));
    }
}
