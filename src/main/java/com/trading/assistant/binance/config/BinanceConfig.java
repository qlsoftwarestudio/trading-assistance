package com.trading.assistant.binance.config;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BinanceConfig {

    @Value("${binance.api.key:}")
    private String apiKey;

    @Value("${binance.api.secret:}")
    private String apiSecret;

    @Value("${binance.api.base-url:https://testnet.binance.vision}")
    private String baseUrl;

    @Bean
    public SpotClient spotClient() {
        if (apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) {
            // Return null client if keys not configured - will work in test/demo mode
            return null;
        }
        return new SpotClientImpl(apiKey, apiSecret, baseUrl);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty();
    }
}
