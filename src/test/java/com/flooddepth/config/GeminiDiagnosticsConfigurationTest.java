package com.flooddepth.config;

import com.flooddepth.controller.GeminiDiagnosticController;
import com.flooddepth.service.ImageStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GeminiDiagnosticsConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @Import(GeminiDiagnosticController.class)
    static class Config { }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class).withBean(ImageStorageService.class, () -> mock(ImageStorageService.class));

    @Test
    void diagnosticControllerIsAbsentByDefaultAndRequiresExplicitOptIn() {
        runner.run(context -> assertEquals(0, context.getBeansOfType(GeminiDiagnosticController.class).size()));
        runner.withPropertyValues("gemini.diagnostics.enabled=true")
                .run(context -> assertEquals(1, context.getBeansOfType(GeminiDiagnosticController.class).size()));
    }
}
