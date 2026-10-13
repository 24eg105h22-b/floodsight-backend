package com.flooddepth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flooddepth.ai.AiResponseParser;
import com.flooddepth.ai.GeminiFloodVisionClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.web.client.RestClient;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class GeminiClientDefaultsTest {
    @Configuration(proxyBeanMethods = false)
    @Import(GeminiFloodVisionClient.class)
    static class Config {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean AiResponseParser parser(ObjectMapper mapper) { return new AiResponseParser(mapper); }
        @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productionDefaultsWorkAndConfiguredModelStillOverridesThem(boolean override) throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                    "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"{\\\"status\\\":\\\"ok\\\"}\"}]}}]}"));
            var runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
                    .withInitializer(context -> {
                        try {
                            context.getEnvironment().getPropertySources().addLast(
                                    new ResourcePropertySource(new ClassPathResource("application.properties")));
                        } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
                    })
                    .withPropertyValues("gemini.enabled=true", "gemini.api-key=unit-test-only-key",
                            "gemini.api-url=" + server.url("/v1beta/models/"));
            if (override) runner = runner.withPropertyValues("gemini.model=gemini-configured-override",
                    "gemini.combined-analysis-timeout-ms=90000");
            String expected = override ? "gemini-configured-override" : "gemini-3.8-flash";
            runner.run(context -> {
                var result = context.getBean(GeminiFloodVisionClient.class)
                        .diagnose(GeminiFloodVisionClient.DiagnosticStage.TEXT, null, null);
                assertTrue(result.success()); assertEquals(expected, result.model());
                assertEquals("60000", context.getEnvironment().getProperty("gemini.timeout-ms"));
                assertEquals(override ? "90000" : "600000",
                        context.getEnvironment().getProperty("gemini.combined-analysis-timeout-ms"));
            });
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request); assertEquals("/v1beta/models/" + expected + ":generateContent", request.getPath());
            assertEquals(1, server.getRequestCount());
        }
    }
}
