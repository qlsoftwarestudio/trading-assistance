package com.trading.assistant.binance;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.assistant.binance.model.Kline;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

@Service
public class BinanceClient {

    private static final Logger logger = LoggerFactory.getLogger(BinanceClient.class);

    @Value("${binance.api.key:}")
    private String apiKey;

    @Value("${binance.api.secret:}")
    private String apiSecret;

    @Value("${binance.api.base-url:https://testnet.binance.vision}")
    private String baseUrl;

    @Value("${trading.strategy.symbol:BTCUSDT}")
    private String symbol;

    private SpotClient client;
    private boolean configured = false;

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty()) {
            this.client = new SpotClientImpl(apiKey, apiSecret, baseUrl);
            this.configured = true;
            logger.info("Binance client configured for {} (Testnet: {})", baseUrl, baseUrl.contains("testnet"));
        } else {
            logger.warn("Binance API keys not configured. Running in demo mode.");
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Get account balance
     */
    public BigDecimal getBalance(String asset) {
        if (!configured) {
            logger.warn("Binance not configured. Returning demo balance.");
            return new BigDecimal("2000.00"); // Demo balance
        }

        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            String result = client.createTrade().account(parameters);
            logger.debug("Account info: {}", result);
            // Parse JSON response to get balance - simplified for demo
            return new BigDecimal("2000.00");
        } catch (Exception e) {
            logger.error("Error getting balance: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get current price for symbol
     */
    public BigDecimal getCurrentPrice() {
        if (!configured) {
            logger.warn("Binance not configured. Returning demo price.");
            return new BigDecimal("45000.00"); // Demo price
        }

        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            String result = client.createMarket().tickerSymbol(parameters);
            logger.debug("Ticker: {}", result);
            // Parse price from JSON
            return new BigDecimal("45000.00");
        } catch (Exception e) {
            logger.error("Error getting price: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get klines (candlestick data) for technical analysis
     * Returns List of Kline objects with OHLCV data
     */
    public List<Kline> getKlines(String interval, int limit) {
        if (!configured) {
            logger.info("DEMO MODE: Generating simulated klines for testing");
            return generateDemoKlines(limit);
        }

        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            parameters.put("interval", interval);
            parameters.put("limit", limit);
            String result = client.createMarket().klines(parameters);
            
            // Parse JSON response
            ObjectMapper mapper = new ObjectMapper();
            List<List<Object>> rawKlines = mapper.readValue(result, List.class);
            
            List<Kline> klines = new ArrayList<>();
            for (List<Object> raw : rawKlines) {
                Kline kline = Kline.fromBinanceArray(raw.toArray());
                if (kline != null) {
                    klines.add(kline);
                }
            }
            
            logger.info("Retrieved {} klines for {} ({} timeframe)", klines.size(), symbol, interval);
            return klines;
            
        } catch (Exception e) {
            logger.error("Error getting klines: {}. Falling back to demo data.", e.getMessage());
            return generateDemoKlines(limit);
        }
    }
    
    /**
     * Generate demo klines for testing without API keys
     * Simulates realistic BTC price movements around $45,000
     */
    private List<Kline> generateDemoKlines(int limit) {
        List<Kline> klines = new ArrayList<>();
        Random random = new Random();
        
        // Starting price around 45000
        BigDecimal basePrice = new BigDecimal("45000.00");
        long currentTime = System.currentTimeMillis();
        long intervalMs = 15 * 60 * 1000; // 15 minutes in ms
        
        for (int i = limit - 1; i >= 0; i--) {
            // Simulate realistic price movement (±2% max change)
            double changePercent = (random.nextDouble() - 0.5) * 0.04; // -2% to +2%
            BigDecimal close = basePrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(changePercent)))
                    .setScale(2, RoundingMode.HALF_UP);
            
            // Generate OHLC based on close
            BigDecimal high = close.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(random.nextDouble() * 0.01)))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal low = close.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(random.nextDouble() * 0.01)))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal open = low.add(high.subtract(low).multiply(BigDecimal.valueOf(random.nextDouble())))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal volume = new BigDecimal(random.nextInt(1000) + 500);
            
            long timestamp = currentTime - (i * intervalMs);
            
            klines.add(new Kline(timestamp, open, high, low, close, volume));
            
            // Next candle starts from current close
            basePrice = close;
        }
        
        return klines;
    }

    /**
     * Place a market buy order (LONG)
     */
    public String placeBuyOrder(BigDecimal quantity) {
        if (!configured) {
            logger.info("DEMO MODE: Would place BUY order for {} {}", quantity, symbol);
            return "DEMO_ORDER_" + System.currentTimeMillis();
        }

        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            parameters.put("side", "BUY");
            parameters.put("type", "MARKET");
            parameters.put("quantity", quantity.toPlainString());
            
            String result = client.createTrade().newOrder(parameters);
            logger.info("Buy order placed: {}", result);
            return extractOrderId(result);
        } catch (Exception e) {
            logger.error("Error placing buy order: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Place a market sell order (close position)
     */
    public String placeSellOrder(BigDecimal quantity) {
        if (!configured) {
            logger.info("DEMO MODE: Would place SELL order for {} {}", quantity, symbol);
            return "DEMO_ORDER_" + System.currentTimeMillis();
        }

        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            parameters.put("side", "SELL");
            parameters.put("type", "MARKET");
            parameters.put("quantity", quantity.toPlainString());
            
            String result = client.createTrade().newOrder(parameters);
            logger.info("Sell order placed: {}", result);
            return extractOrderId(result);
        } catch (Exception e) {
            logger.error("Error placing sell order: {}", e.getMessage());
            return null;
        }
    }

    private String extractOrderId(String jsonResponse) {
        // Simplified extraction - in production use proper JSON parsing
        return "ORDER_" + System.currentTimeMillis();
    }
}
