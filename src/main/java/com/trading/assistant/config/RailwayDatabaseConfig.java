package com.trading.assistant.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;

/**
 * Converts Railway's DATABASE_URL (postgres://...) to Spring's JDBC URL format.
 * Railway sets DATABASE_URL when a PostgreSQL service is attached.
 */
@Configuration
@Profile("!test")
public class RailwayDatabaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(RailwayDatabaseConfig.class);

    @PostConstruct
    public void convertRailwayDatabaseUrl() {
        String databaseUrl = System.getenv("DATABASE_URL");
        String springDatasourceUrl = System.getenv("SPRING_DATASOURCE_URL");

        if (springDatasourceUrl != null && !springDatasourceUrl.isBlank()) {
            logger.info("SPRING_DATASOURCE_URL is already set. Skipping DATABASE_URL conversion.");
            return;
        }

        if (databaseUrl == null || databaseUrl.isBlank()) {
            logger.warn("Neither SPRING_DATASOURCE_URL nor DATABASE_URL is set. PostgreSQL connection will fail.");
            return;
        }

        try {
            // Convert postgres://user:pass@host:port/db -> jdbc:postgresql://host:port/db?user=...&password=...
            URI uri = new URI(databaseUrl);
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

            String jdbcUrl = String.format(
                "jdbc:postgresql://%s:%d%s?user=%s&password=%s&sslmode=require",
                host, port, path, user, password
            );

            System.setProperty("SPRING_DATASOURCE_URL", jdbcUrl);
            logger.info("Converted Railway DATABASE_URL to JDBC URL: jdbc:postgresql://{}:{}{}", host, port, path);
        } catch (Exception e) {
            logger.error("Failed to convert DATABASE_URL to JDBC format: {}", e.getMessage());
        }
    }
}
