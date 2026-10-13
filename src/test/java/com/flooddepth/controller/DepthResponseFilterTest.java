package com.flooddepth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class DepthResponseFilterTest {
    final DepthResponseFilter filter = new DepthResponseFilter();
    MockHttpServletRequest request() { return new MockHttpServletRequest("POST","/api/flood/estimate-depth"); }
    @Test void earlyContainerErrorIsJsonWithoutRawMessage() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(),response,(req,res)->((jakarta.servlet.http.HttpServletResponse)res).sendError(400,"mock-private-provider"));
        assertEquals(400,response.getStatus()); assertEquals("application/json",response.getContentType().split(";")[0]);
        assertEquals("ERROR",new ObjectMapper().readTree(response.getContentAsString()).path("status").textValue());
        assertFalse(response.getContentAsString().contains("mock-private")); assertNull(MDC.get("depthRequestId"));
    }
    @Test void failureDuringSerializationDiscardsPartialJsonAndReturnsErrorJson() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(),response,(req,res)->{
            res.setContentType("application/json"); res.getOutputStream().write("{\"status\":".getBytes());
            throw new IllegalStateException("mock-private-key");
        });
        assertEquals(500,response.getStatus());
        assertEquals("DEPTH_ANALYSIS_FAILED",new ObjectMapper().readTree(response.getContentAsString()).path("code").textValue());
        assertFalse(response.getContentAsString().contains("mock-private"));
    }
    @Test void htmlAndProblemDetailsBecomeApplicationErrors() throws Exception {
        for(String type:new String[]{"text/html","application/problem+json"}) {
            var response=new MockHttpServletResponse();
            filter.doFilter(request(),response,(req,res)->{
                ((jakarta.servlet.http.HttpServletResponse)res).setStatus(415); res.setContentType(type);
                res.getOutputStream().write("mock-private-response".getBytes());
            });
            assertEquals(415,response.getStatus()); assertEquals("application/json",response.getContentType().split(";")[0]);
            assertEquals("INVALID_REQUEST",new ObjectMapper().readTree(response.getContentAsString()).path("code").textValue());
            assertFalse(response.getContentAsString().contains("mock-private"));
        }
    }
    @Test void otherEndpointsAreUntouchedAndSuccessfulJsonIsByteForBytePreserved() throws Exception {
        var legacy = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/flood-reports"),legacy,(req,res)->{
            res.setContentType("text/plain"); res.getWriter().write("legacy");
        }); assertEquals("legacy",legacy.getContentAsString()); assertEquals("text/plain",legacy.getContentType());
        var success=new MockHttpServletResponse(); String json="{\"status\":\"INSUFFICIENT_EVIDENCE\"}";
        filter.doFilter(request(),success,(req,res)->{res.setContentType("application/json");res.getOutputStream().write(json.getBytes());});
        assertEquals(200,success.getStatus()); assertEquals(json,success.getContentAsString());
    }
}
