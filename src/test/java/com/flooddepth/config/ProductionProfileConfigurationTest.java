package com.flooddepth.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionProfileConfigurationTest {

    @Test
    void productionProfileUsesMysqlConfigurationAndRequiredEnvironmentVariables() throws IOException {
        Path propertiesPath = Path.of("src", "main", "resources", "application-production.properties");
        String productionProperties = Files.readString(propertiesPath, StandardCharsets.UTF_8);

        assertThat(productionProperties)
                .contains("spring.datasource.url=${DB_URL:}")
                .contains("spring.datasource.username=${DB_USERNAME:}")
                .contains("spring.datasource.password=${DB_PASSWORD:}")
                .contains("spring.datasource.driver-class-name=${DB_DRIVER_CLASS_NAME:com.mysql.cj.jdbc.Driver}")
                .contains("spring.datasource.hikari.data-source-properties.sslMode=${DB_SSL_MODE:REQUIRED}")
                .contains("spring.datasource.hikari.data-source-properties.useSSL=${DB_USE_SSL:true}")
                .contains("spring.datasource.hikari.data-source-properties.requireSSL=${DB_REQUIRE_SSL:true}")
                .contains("spring.datasource.hikari.data-source-properties.serverTimezone=${DB_SERVER_TIMEZONE:UTC}")
                .contains("spring.jpa.database-platform=${JPA_DATABASE_PLATFORM:org.hibernate.dialect.MySQLDialect}")
                .doesNotContain("jdbc:h2")
                .doesNotContain("org.h2.Driver");

        Path basePropertiesPath = Path.of("src", "main", "resources", "application.properties");
        String baseProperties = Files.readString(basePropertiesPath, StandardCharsets.UTF_8);
        assertThat(baseProperties)
                .contains("spring.profiles.active=${SPRING_PROFILES_ACTIVE:local}");
    }
}
