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

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String databaseUrl = System.getenv(DATABASE_URL);

        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }

        // If PGHOST or DB_HOST is already set, Railway likely provided separated vars already
        if (environment.getProperty("PGHOST") != null || environment.getProperty("DB_HOST") != null) {
            return;
        }

        Map<String, Object> properties = convertToProperties(databaseUrl);
        if (properties.isEmpty()) {
            return;
        }

        MapPropertySource propertySource = new MapPropertySource("railwayDatabaseUrl", properties);
        environment.getPropertySources().addFirst(propertySource);
    }

    private Map<String, Object> convertToProperties(String databaseUrl) {
        Map<String, Object> properties = new HashMap<>();
        try {
            String normalized = databaseUrl.replaceFirst("^postgres://", "postgresql://");
            URI uri = new URI(normalized);

            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String path = uri.getPath();
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            String userInfo = uri.getUserInfo();
            String user = "";
            String password = "";
            if (userInfo != null && userInfo.contains(":")) {
                String[] parts = userInfo.split(":");
                user = parts[0];
                password = parts[1];
            }

            // Inject both PG* (Railway native) and DB_* (manual config) vars
            properties.put("PGHOST", host);
            properties.put("PGPORT", String.valueOf(port));
            properties.put("PGDATABASE", path);
            properties.put("PGUSER", user);
            properties.put("PGPASSWORD", password);

            properties.put("DB_HOST", host);
            properties.put("DB_PORT", String.valueOf(port));
            properties.put("DB_NAME", path);
            properties.put("DB_USER", user);
            properties.put("DB_PASSWORD", password);
        } catch (Exception e) {
            System.err.println("Failed to parse DATABASE_URL: " + e.getMessage());
        }
        return properties;
    }
}
