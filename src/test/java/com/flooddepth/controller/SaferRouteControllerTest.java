package com.flooddepth.controller;

import com.flooddepth.saferoute.*;
import com.flooddepth.saferoute.SaferRouteModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SaferRouteController.class)
class SaferRouteControllerTest {
    @Autowired MockMvc mvc;@MockitoBean SaferRouteService service;
    @Test void threeInputJsonAndLocalCorsReturnCleanApplicationDto() throws Exception {
        var route=new AssessedRoute("route-1",1000,120,Risk.LOW,0,0,0,0,List.of(new Point(10,20),new Point(10,20.01)),"??", "No significant flood evidence",List.of());
        when(service.plan(any())).thenReturn(new Response(new Point(10,20),new Destination("place",10,20.01),"route-1",List.of(route),List.of("Limited flood reports may reduce coverage.")));
        mvc.perform(post("/api/routes/safer").header("Origin","http://127.0.0.1:5173").contentType("application/json").content("{\"startLatitude\":10,\"startLongitude\":20,\"destinationText\":\"place\"}"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://127.0.0.1:5173"))
            .andExpect(jsonPath("$.recommendedRouteId").value("route-1")).andExpect(jsonPath("$.routes[0].riskScore").value(0))
            .andExpect(jsonPath("$.routes[0].riskLevel").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.routes[0].assessmentState").value("LIMITED_EVIDENCE"))
            .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.provider").doesNotExist()).andExpect(jsonPath("$.routes[0].evidence[0].reportId").doesNotExist());
    }
    @Test void providerFailureCannotEchoSecretBodiesOrStackTraces() throws Exception {
        when(service.plan(any())).thenThrow(new IllegalStateException("private-key provider-url raw-body"));
        mvc.perform(post("/api/routes/safer").contentType("application/json").content("{\"startLatitude\":10,\"startLongitude\":20,\"destinationText\":\"place\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("Route planning is temporarily unavailable. Please try again."))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-key"))))
            .andExpect(jsonPath("$.trace").doesNotExist());
    }
    @Test void missingDestinationAndNoRouteHaveDistinctSafeErrors() throws Exception {
        for(var code:List.of(RoutePlanningException.Code.DESTINATION_NOT_FOUND,RoutePlanningException.Code.NO_ROUTES,RoutePlanningException.Code.INVALID_START)){
            doThrow(new RoutePlanningException(code)).when(service).plan(any());
            mvc.perform(post("/api/routes/safer").contentType("application/json").content("{}"))
                .andExpect(status().is(code.status)).andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.message").value(code.message));
        }
    }
    @Test void malformedJsonFailsBeforeExternalCalls() throws Exception {
        mvc.perform(post("/api/routes/safer").contentType("application/json").content("{broken")).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
}
