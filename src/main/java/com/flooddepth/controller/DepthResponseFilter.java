package com.flooddepth.controller;

import com.flooddepth.depth.DepthDiagnostics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Covers failures before handler selection and serialization as well as controller errors. */
@Component
public class DepthResponseFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath()+"/api/flood/estimate-depth");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var buffered = new ContentCachingResponseWrapper(response) {
            // Avoid the servlet container committing an HTML error page before JSON normalization.
            @Override public void sendError(int status) { setStatus(status); }
            @Override public void sendError(int status, String message) { setStatus(status); }
        };
        MDC.put("depthRequestId", UUID.randomUUID().toString());
        DepthDiagnostics.stage(DepthDiagnostics.Stage.REQUEST);
        try {
            try { chain.doFilter(request, buffered); }
            catch (Exception failure) {
                // No exception content, headers, request body or provider URL is inspected or logged.
                DepthDiagnostics.failed("UNHANDLED_EXCEPTION", null);
                buffered.resetBuffer(); buffered.setStatus(500);
            }
            String type = buffered.getContentType();
            boolean json = type != null && (type.startsWith("application/json") || type.startsWith("application/problem+json"));
            boolean successfulPreflight = request.getMethod().equals("OPTIONS") && buffered.getStatus() < 400;
            if (!successfulPreflight && (buffered.getContentSize() == 0 || !json
                    || (buffered.getStatus() >= 400 && type.startsWith("application/problem+json")))) {
                int status = buffered.getStatus() >= 400 ? buffered.getStatus() : 500;
                DepthDiagnostics.failed("NON_JSON_OR_EMPTY_HTTP_BODY", null);
                buffered.resetBuffer(); buffered.setStatus(status); buffered.setContentType("application/json");
                buffered.setCharacterEncoding(StandardCharsets.UTF_8.name());
                String code = status == 403 ? "REQUEST_NOT_ALLOWED" : status < 500 ? "INVALID_REQUEST" : "DEPTH_ANALYSIS_FAILED";
                buffered.getOutputStream().write(("{\"status\":\"ERROR\",\"code\":\""+code
                        +"\",\"message\":\"FloodSight could not complete the depth analysis.\"}").getBytes(StandardCharsets.UTF_8));
            }
            DepthDiagnostics.logger().info("DEPTH_RESPONSE_SERIALIZED requestId={} httpStatus={} contentType={} bytes={}",
                    MDC.get("depthRequestId"), buffered.getStatus(), buffered.getContentType(), buffered.getContentSize());
            buffered.copyBodyToResponse();
        } finally { MDC.remove("depthRequestId"); MDC.remove("depthStage"); }
    }
}
