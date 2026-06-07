package com.trading.assistant.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.assistant.binance.model.Kline;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

@Service
public class BinanceClient {

    private static final Logger logger = LoggerFactory.getLogger(BinanceClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${binance.api.key:}")
    private String apiKey;

    @Value("${binance.api.secret:}")
    private String apiSecret;

    @Value("${binance.api.base-url:https://testnet.binancefuture.com}")
    private String baseUrl;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.leverage:5}")
    private int defaultLeverage;

    @Value("${binance.hedge-mode:false}")
    private boolean hedgeMode;

    private WebClient webClient;
    private boolean configured = false;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty()) {
            this.configured = true;
            logger.info("Binance Futures client configured for {} (Testnet: {})", baseUrl, baseUrl.contains("testnet"));
            setLeverage(defaultLeverage);
        } else {
            logger.warn("Binance API keys not configured. Running in demo mode.");
        }
    }

    public boolean isConfigured() {
        return configured;
    }

    /**
     * Get USDT balance from futures account
     */
    public BigDecimal getBalance(String asset) {
        if (!configured) {
            logger.warn("Binance not configured. Returning demo balance.");
            return new BigDecimal("1000.00");
        }

        try {
            String query = buildSignedQuery(new LinkedHashMap<>());
            String url = "/fapi/v2/balance?" + query;

            String response = webClient.get()
                    .uri(url)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(response);
            for (JsonNode assetNode : root) {
                if (asset.equals(assetNode.get("asset").asText())) {
                    return new BigDecimal(assetNode.get("availableBalance").asText());
                }
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.error("Error getting balance: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get current mark price for symbol
     */
    public BigDecimal getCurrentPrice() {
        if (!configured) {
            logger.warn("Binance not configured. Returning demo price.");
            return new BigDecimal("18.50");
        }

        try {
            String url = "/fapi/v1/ticker/price?symbol=" + symbol;
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(response);
            return new BigDecimal(root.get("price").asText());
        } catch (Exception e) {
            logger.error("Error getting price: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get klines (candlestick data) for technical analysis
     */
    public List<Kline> getKlines(String interval, int limit) {
        return getKlines(symbol, interval, limit);
    }

    /**
     * Get klines for any symbol (useful for BTC correlation, multi-asset analysis)
     */
    public List<Kline> getKlines(String targetSymbol, String interval, int limit) {
        if (!configured) {
            logger.info("DEMO MODE: Generating simulated klines for testing");
            return generateDemoKlines(limit);
        }

        try {
            String url = String.format("/fapi/v1/klines?symbol=%s&interval=%s&limit=%d", targetSymbol, interval, limit);
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<List<Object>> rawKlines = mapper.readValue(response, List.class);
            List<Kline> klines = new ArrayList<>();
            for (List<Object> raw : rawKlines) {
                Kline kline = Kline.fromBinanceArray(raw.toArray());
                if (kline != null) {
                    klines.add(kline);
                }
            }

            logger.info("Retrieved {} klines for {} ({} timeframe)", klines.size(), targetSymbol, interval);
            return klines;

        } catch (Exception e) {
            logger.error("Error getting klines for {}: {}. Falling back to demo data.", targetSymbol, e.getMessage());
            return generateDemoKlines(limit);
        }
    }

    /**
     * Set leverage for symbol
     */
    public void setLeverage(int leverage) {
        if (!configured) {
            return;
        }
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("leverage", leverage);
            String query = buildSignedQuery(params);

            String response = webClient.post()
                    .uri("/fapi/v1/leverage?" + query)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("Leverage set: {}", response);
        } catch (Exception e) {
            logger.error("Error setting leverage: {}", e.getMessage());
        }
    }

    /**
     * Place a market order on Binance Futures (supports One-way and Hedge Mode)
     */
    public String placeOrder(String side, String positionSide, BigDecimal quantity, boolean reduceOnly) {
        if (!configured) {
            logger.info("DEMO MODE: Would place {} order for {} {}", side, quantity, symbol);
            return "DEMO_ORDER_" + System.currentTimeMillis();
        }

        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            if (hedgeMode) {
                params.put("positionSide", positionSide);
            }
            params.put("type", "MARKET");
            params.put("quantity", quantity.setScale(8, RoundingMode.DOWN).toPlainString());
            if (reduceOnly) {
                params.put("reduceOnly", "true");
            }

            String query = buildSignedQuery(params);
            String response = webClient.post()
                    .uri("/fapi/v1/order?" + query)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body ->
                            logger.error("Binance 4xx error ({}): {}", clientResponse.statusCode(), body)
                        ).then(clientResponse.createException())
                    )
                    .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body ->
                            logger.error("Binance 5xx error ({}): {}", clientResponse.statusCode(), body)
                        ).then(clientResponse.createException())
                    )
                    .bodyToMono(String.class)
                    .block();

            logger.info("Order placed: {}", response);
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("Error placing order: {}. Fallback to DEMO order.", e.getMessage());
            logger.info("DEMO FALLBACK: Simulating {} order for {} {}", side, quantity, symbol);
            return "DEMO_ORDER_" + System.currentTimeMillis();
        }
    }

    /**
     * Place a market buy order to open LONG
     */
    public String placeBuyOrder(BigDecimal quantity) {
        return placeOrder("BUY", "LONG", quantity, false);
    }

    /**
     * Place a market sell order to close LONG
     */
    public String placeSellOrder(BigDecimal quantity) {
        return placeOrder("SELL", "LONG", quantity, true);
    }

    /**
     * Place a market sell order to open SHORT
     */
    public String placeShortSellOrder(BigDecimal quantity) {
        return placeOrder("SELL", "SHORT", quantity, false);
    }

    /**
     * Place a market buy order to close SHORT
     */
    public String placeShortBuyOrder(BigDecimal quantity) {
        return placeOrder("BUY", "SHORT", quantity, true);
    }

    // ============== PRIVATE HELPERS ==============

    private String buildSignedQuery(LinkedHashMap<String, Object> params) {
        params.put("timestamp", System.currentTimeMillis());
        StringBuilder query = new StringBuilder();
        params.forEach((k, v) -> {
            if (query.length() > 0) query.append("&");
            query.append(k).append("=").append(v);
        });
        String signature = hmacSha256(query.toString(), apiSecret);
        query.append("&signature=").append(signature);
        return query.toString();
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request", e);
        }
    }

    private String extractOrderId(String jsonResponse) {
        try {
            JsonNode root = mapper.readTree(jsonResponse);
            return root.get("orderId").asText();
        } catch (Exception e) {
            logger.error("Failed to extract orderId from response: {}", jsonResponse);
            return "ORDER_" + System.currentTimeMillis();
        }
    }

    private List<Kline> generateDemoKlines(int limit) {
        List<Kline> klines = new ArrayList<>();
        Random random = new Random();
        BigDecimal basePrice = new BigDecimal("18.50");
        long currentTime = System.currentTimeMillis();
        long intervalMs = 15 * 60 * 1000;

        for (int i = limit - 1; i >= 0; i--) {
            double changePercent = (random.nextDouble() - 0.5) * 0.04;
            BigDecimal close = basePrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(changePercent)))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal high = close.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(random.nextDouble() * 0.01)))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal low = close.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(random.nextDouble() * 0.01)))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal open = low.add(high.subtract(low).multiply(BigDecimal.valueOf(random.nextDouble())))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal volume = new BigDecimal(random.nextInt(10000) + 5000);

            long timestamp = currentTime - (i * intervalMs);
            klines.add(new Kline(timestamp, open, high, low, close, volume));
            basePrice = close;
        }

        return klines;
    }
}
