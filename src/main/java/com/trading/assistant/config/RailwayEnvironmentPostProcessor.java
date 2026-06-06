package com.trading.assistant.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts Railway's DATABASE_URL (postgresql:// or postgres://) to a valid JDBC URL
 * before Spring Boot creates the datasource. Runs before ApplicationContext starts.
 */
public class RailwayEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DATABASE_URL = "DATABASE_URL";
    private static final String SPRING_DATASOURCE_URL = "spring.datasource.url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = System.getenv(DATABASE_URL);
        String existingSpringUrl = environment.getProperty(SPRING_DATASOURCE_URL);

        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }

        // If spring.datasource.url is already a valid jdbc URL, skip
        if (existingSpringUrl != null && existingSpringUrl.startsWith("jdbc:")) {
            return;
        }

        String jdbcUrl = convertToJdbcUrl(databaseUrl);
        if (jdbcUrl == null) {
            return;
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(SPRING_DATASOURCE_URL, jdbcUrl);

        MapPropertySource propertySource = new MapPropertySource("railwayDatabaseUrl", properties);
        environment.getPropertySources().addFirst(propertySource);
    }

    private String convertToJdbcUrl(String databaseUrl) {
        try {
            // Railway may use postgresql:// or postgres:// scheme
            String normalized = databaseUrl.replaceFirst("^postgres://", "postgresql://");
            URI uri = new URI(normalized);

            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath();

            String userInfo = uri.getUserInfo();
            String user = "";
            String password = "";
            if (userInfo != null && userInfo.contains(":")) {
                String[] parts = userInfo.split(":");
                user = parts[0];
                password = parts[1];
            }

            return String.format(
                "jdbc:postgresql://%s:%d%s?user=%s&password=%s&sslmode=require",
                host, port, path, user, password
            );
        } catch (Exception e) {
            System.err.println("Failed to convert DATABASE_URL to JDBC format: " + e.getMessage());
            return null;
        }
    }
}
