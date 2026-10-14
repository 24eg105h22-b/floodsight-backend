package com.flooddepth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Keep local-only Gemini diagnostics outside the browser API policy.
        for (String path : new String[]{"/api/test", "/api/flood/**", "/api/mapillary/**",
                "/api/flood-reports/**", "/api/routes/**"}) {
            registry.addMapping(path)
                    .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                            "https://floodsight-frontend.vercel.app")
                    .allowedMethods("GET", "HEAD", "POST", "DELETE", "OPTIONS")
                    .allowedHeaders("Content-Type", "Accept");
        }
    }
}
