package com.flooddepth.ai;

import com.flooddepth.depth.DepthDiagnostics;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

/** Per-attempt scalar observations. No header values, request bodies, response content or causes are logged. */
final class GeminiTransportDiagnostics {
    private final String stage;
    private final long start;
    private final Instant startedAt = Instant.now();
    private final int connectMs, readMs;
    private final int attempt;
    private Integer status;
    private boolean bodyReading, bodyReceived;
    GeminiTransportDiagnostics(String stage, long start, int connectMs, int readMs) {
        this.stage = stage; this.start = start; this.connectMs = connectMs; this.readMs = readMs;
        String number = MDC.get("geminiAttempt");
        this.attempt = number != null && number.matches("[1-3]") ? Integer.parseInt(number) : 1;
        DepthDiagnostics.logger().info("GEMINI_ATTEMPT_START requestId={} stage={} attempt={} startedAt={} connectTimeoutMs={} readTimeoutMs={} combinedAnalysisTimeoutMs={} totalRequestTimeoutMs=none",
                MDC.get("depthRequestId"), stage, attempt, startedAt, connectMs, readMs, combinedBudget());
    }
    Integer status() { return status; }
    boolean bodyReceived() { return bodyReceived; }
    ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            var response = execution.execute(request, body);
            status = response.getStatusCode().value();
            DepthDiagnostics.logger().info("GEMINI_RESPONSE_START requestId={} stage={} attempt={} providerHttpStatus={} elapsedMs={}",
                    MDC.get("depthRequestId"), stage, attempt, status, elapsed());
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                @Override public void close() { response.close(); }
                @Override public InputStream getBody() throws IOException {
                    bodyReading = true;
                    return new FilterInputStream(response.getBody()) {
                        @Override public int read() throws IOException {
                            int value = super.read(); if (value >= 0) bodyReceived = true; return value;
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            int count = super.read(bytes, offset, length); if (count > 0) bodyReceived = true; return count;
                        }
                    };
                }
            };
        };
    }
    void timeout(Throwable failure) {
        DepthDiagnostics.logger().warn("GEMINI_ATTEMPT_TIMEOUT requestId={} stage={} attempt={} startedAt={} timeoutAt={} elapsedMs={} timeoutType={} connectTimeoutMs={} readTimeoutMs={} combinedAnalysisTimeoutMs={} providerHttpStatus={} responseStarted={} anyResponseBodyReceived={}",
                MDC.get("depthRequestId"), stage, attempt, startedAt, Instant.now(), elapsed(), type(failure),
                connectMs, readMs, combinedBudget(), status, status != null, bodyReceived);
    }
    private String combinedBudget() {
        return "COMBINED_ANALYSIS".equals(stage) ? Integer.toString(readMs) : "not_applicable";
    }
    String type(Throwable cause) {
        for (int i = 0; cause != null && i < 12; i++, cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpConnectTimeoutException) return "CONNECT_TIMEOUT";
            if (cause instanceof java.net.http.HttpTimeoutException) return "REQUEST_TIMEOUT";
            if (cause instanceof java.net.SocketTimeoutException) {
                if (status != null && bodyReading) return "BODY_TIMEOUT";
                // Inspect only canonical phase markers; never record arbitrary exception messages.
                if ("connect timed out".equalsIgnoreCase(cause.getMessage())) return "CONNECT_TIMEOUT";
                if ("Read timed out".equalsIgnoreCase(cause.getMessage())) return "READ_TIMEOUT";
                return "OTHER";
            }
        }
        return "OTHER";
    }
    private long elapsed() { return (System.nanoTime() - start) / 1_000_000; }
}
