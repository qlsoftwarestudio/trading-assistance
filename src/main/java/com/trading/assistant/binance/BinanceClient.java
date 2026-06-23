package com.trading.assistant.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.assistant.binance.model.BookTicker;
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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
    private boolean testnetMode = false;
    private int quantityPrecision = 1;
    private final Map<String, String> algoOrderTypes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty()) {
            this.configured = true;
            this.testnetMode = baseUrl.contains("testnet") || baseUrl.contains("demo-fapi");
            logger.info("Binance Futures client configured for {} (Testnet: {})", baseUrl, testnetMode);
            fetchQuantityPrecision();
            setLeverage(defaultLeverage);
        } else {
            logger.warn("Binance API keys not configured. Running in demo mode.");
        }
    }

    private void fetchQuantityPrecision() {
        try {
            String response = webClient.get()
                    .uri("/fapi/v1/exchangeInfo")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            for (JsonNode s : root.get("symbols")) {
                if (symbol.equals(s.get("symbol").asText())) {
                    quantityPrecision = s.get("quantityPrecision").asInt();
                    logger.info("Symbol {} quantityPrecision: {}", symbol, quantityPrecision);
                    return;
                }
            }
            logger.warn("Symbol {} not found in exchangeInfo, using default precision: {}", symbol, quantityPrecision);
        } catch (Exception e) {
            logger.warn("Could not fetch quantityPrecision for {}: {}. Using default: {}", symbol, e.getMessage(), quantityPrecision);
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
     * Get price for any symbol (multi-pair support)
     */
    public BigDecimal getPrice(String targetSymbol) {
        if (!configured) {
            logger.warn("Binance not configured. Returning demo price.");
            return new BigDecimal("18.50");
        }
        try {
            String url = "/fapi/v1/ticker/price?symbol=" + targetSymbol;
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            return new BigDecimal(root.get("price").asText());
        } catch (Exception e) {
            logger.error("Error getting price for {}: {}", targetSymbol, e.getMessage());
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
            params.put("quantity", quantity.setScale(quantityPrecision, RoundingMode.DOWN).toPlainString());
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
            logger.error("Error placing order: {}", e.getMessage());
            if (!configured) {
                logger.info("DEMO FALLBACK: Simulating {} order for {} {}", side, quantity, symbol);
                return "DEMO_ORDER_" + System.currentTimeMillis();
            }
            return null;
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

    /**
     * Place a market order for a specific symbol (multi-symbol support).
     * Uses a default quantity precision of 2 for unknown symbols.
     */
    public String placeOrderForSymbol(String sym, String side, String positionSide, BigDecimal quantity, boolean reduceOnly) {
        if (!configured) {
            logger.info("DEMO MODE: Would place {} {} order for {} {}", side, sym, quantity, sym);
            return "DEMO_ORDER_" + System.currentTimeMillis();
        }
        try {
            int symPrecision = sym.equals(this.symbol) ? this.quantityPrecision : 2;
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", sym);
            params.put("side", side);
            if (hedgeMode) {
                params.put("positionSide", positionSide);
            }
            params.put("type", "MARKET");
            params.put("quantity", quantity.setScale(symPrecision, RoundingMode.DOWN).toPlainString());
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
                    .bodyToMono(String.class)
                    .block();
            logger.info("[Bot] Order placed ({}): {}", sym, response);
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("Error placing order for {}: {}", sym, e.getMessage());
            return null;
        }
    }

    public String placeSellOrderForSymbol(String sym, BigDecimal quantity) {
        return placeOrderForSymbol(sym, "SELL", "LONG", quantity, true);
    }

    public String placeShortBuyOrderForSymbol(String sym, BigDecimal quantity) {
        return placeOrderForSymbol(sym, "BUY", "SHORT", quantity, true);
    }

    // ============== USER DATA STREAM ==============

    public String createListenKey() {
        if (!configured) {
            return "DEMO_LISTEN_KEY";
        }
        try {
            String response = webClient.post()
                    .uri("/fapi/v1/listenKey")
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            String key = root.get("listenKey").asText();
            logger.info("User Data Stream listenKey created.");
            return key;
        } catch (Exception e) {
            logger.error("Error creating listen key: {}", e.getMessage());
            return null;
        }
    }

    public boolean keepAliveListenKey(String listenKey) {
        if (!configured) {
            return true;
        }
        try {
            webClient.put()
                    .uri("/fapi/v1/listenKey?listenKey=" + listenKey)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            logger.error("Error keeping alive listen key: {}", e.getMessage());
            return false;
        }
    }

    // ============== CONDITIONAL ORDERS ==============

    public String placeStopLossOrder(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice) {
        String orderId = placeConditionalOrder(side, positionSide, quantity, stopPrice, "STOP_MARKET");
        if (orderId == null) {
            logger.warn("STOP_MARKET not supported, falling back to STOP (limit conditional) for testnet");
            orderId = placeConditionalOrder(side, positionSide, quantity, stopPrice, "STOP");
        }
        return orderId;
    }

    public String placeTakeProfitOrder(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice) {
        String orderId = placeConditionalOrder(side, positionSide, quantity, stopPrice, "TAKE_PROFIT_MARKET");
        if (orderId == null) {
            logger.warn("TAKE_PROFIT_MARKET not supported, falling back to TAKE_PROFIT (limit conditional) for testnet");
            orderId = placeConditionalOrder(side, positionSide, quantity, stopPrice, "TAKE_PROFIT");
        }
        return orderId;
    }

    private String placeConditionalOrder(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice, String type) {
        if (!configured) {
            logger.info("DEMO MODE: Would place {} {} order at {}", type, side, stopPrice);
            return "DEMO_ORDER_" + System.currentTimeMillis();
        }
        if (testnetMode) {
            logger.info("TESTNET: Conditional orders not supported by Binance testnet. Using local polling for {} {} at {}", type, side, stopPrice);
            return "TESTNET_" + type + "_" + System.currentTimeMillis();
        }
        AtomicReference<String> errorBodyRef = new AtomicReference<>();
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            if (hedgeMode) {
                params.put("positionSide", positionSide);
            }
            params.put("type", type);
            params.put("quantity", quantity.setScale(quantityPrecision, RoundingMode.DOWN).toPlainString());
            params.put("reduceOnly", "true");
            params.put("stopPrice", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());

            if ("STOP".equals(type) || "TAKE_PROFIT".equals(type)) {
                params.put("price", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());
                params.put("timeInForce", "GTC");
            }

            String query = buildSignedQuery(params);
            String response = webClient.post()
                    .uri("/fapi/v1/order?" + query)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body -> {
                            errorBodyRef.set(body);
                            logger.error("Binance 4xx placing {} ({}): {}", type, clientResponse.statusCode(), body);
                            if (body.contains("-4120") || body.contains("not supported") || body.contains("Algo Order API")) {
                                logger.warn("Order type {} not supported on /fapi/v1/order, will try /fapi/v1/algoOrder", type);
                            }
                        }).then(clientResponse.createException())
                    )
                    .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body -> {
                            errorBodyRef.set(body);
                            logger.error("Binance 5xx placing {} ({}): {}", type, clientResponse.statusCode(), body);
                        }).then(clientResponse.createException())
                    )
                    .bodyToMono(String.class)
                    .block();

            logger.info("{} order placed: {}", type, response);
            return extractOrderId(response);
        } catch (Exception e) {
            String errorDetails = errorBodyRef.get();
            if (errorDetails == null) {
                errorDetails = e.getMessage();
            }
            if (errorDetails != null && (errorDetails.contains("-4120") || errorDetails.contains("not supported") || errorDetails.contains("Algo Order API"))) {
                logger.warn("Order type {} not supported on /fapi/v1/order, falling back to /fapi/v1/algoOrder: {}", type, errorDetails);
                return placeConditionalOrderViaAlgo(side, positionSide, quantity, stopPrice, type);
            }
            logger.error("Error placing {} order: {}", type, e.getMessage(), e);
            return null;
        }
    }

    private String placeConditionalOrderViaAlgo(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice, String type) {
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            if (hedgeMode) {
                params.put("positionSide", positionSide);
            }
            params.put("algotype", "CONDITIONAL");
            params.put("orderType", type);
            params.put("quantity", quantity.setScale(quantityPrecision, RoundingMode.DOWN).toPlainString());
            params.put("reduceOnly", "true");
            params.put("triggerPrice", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());
            params.put("workingType", "CONTRACT_PRICE");

            if ("STOP".equals(type) || "TAKE_PROFIT".equals(type)) {
                params.put("price", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());
                params.put("timeInForce", "GTC");
            }

            String query = buildSignedQuery(params);
            String response = webClient.post()
                    .uri("/fapi/v1/algoOrder?" + query)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body ->
                            logger.error("Binance 4xx placing algo {} ({}): {}", type, clientResponse.statusCode(), body)
                        ).then(clientResponse.createException())
                    )
                    .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body ->
                            logger.error("Binance 5xx placing algo {} ({}): {}", type, clientResponse.statusCode(), body)
                        ).then(clientResponse.createException())
                    )
                    .bodyToMono(String.class)
                    .block();

            logger.info("Algo {} order placed: {}", type, response);
            String algoId = extractAlgoId(response);
            if (algoId != null) {
                algoOrderTypes.put(algoId, type);
            }
            return algoId;
        } catch (Exception e) {
            logger.error("Error placing algo {} order: {}", type, e.getMessage(), e);
            return null;
        }
    }

    public boolean cancelOrder(String orderId) {
        if (!configured) {
            return true;
        }
        if (orderId != null && orderId.startsWith("TESTNET_")) {
            logger.info("TESTNET: Skipping cancel for dummy order {}", orderId);
            return true;
        }
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", orderId);
            String query = buildSignedQuery(params);

            webClient.delete()
                    .uri("/fapi/v1/order?" + query)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("Order {} cancelled.", orderId);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to cancel order {} via /fapi/v1/order: {}, trying /fapi/v1/algoOrder", orderId, e.getMessage());
            return cancelAlgoOrder(orderId);
        }
    }

    private boolean cancelAlgoOrder(String orderId) {
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("algoId", orderId);
            params.put("algotype", "CONDITIONAL");
            String query = buildSignedQuery(params);

            webClient.delete()
                    .uri("/fapi/v1/algoOrder?" + query)
                    .header("X-MBX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("Algo order {} cancelled.", orderId);
            algoOrderTypes.remove(orderId);
            return true;
        } catch (Exception e) {
            logger.error("Error cancelling algo order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    // ============== MARKET DATA ==============

    /**
     * Get best bid/ask (book ticker) for spread calculation.
     * Endpoint: GET /fapi/v1/ticker/bookTicker?symbol=HYPEUSDT
     * Returns: { "symbol": "BTCUSDT", "bidPrice": "...", "askPrice": "...", "bidQty": "...", "askQty": "...", "time": 1589437530011 }
     */
    public BookTicker getBookTicker() {
        if (!configured) {
            logger.warn("Binance not configured. Returning demo book ticker.");
            return new BookTicker(symbol, new BigDecimal("59.99"), new BigDecimal("60.01"));
        }
        try {
            String url = "/fapi/v1/ticker/bookTicker?symbol=" + symbol;
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(response);
            String bidPrice = root.get("bidPrice").asText();
            String askPrice = root.get("askPrice").asText();
            return new BookTicker(symbol, new BigDecimal(bidPrice), new BigDecimal(askPrice));
        } catch (Exception e) {
            logger.error("Error getting book ticker: {}", e.getMessage());
            return null;
        }
    }

    // ============== PER-BOT ORDER PLACEMENT ==============

    /**
     * Place a market order using bot-specific API credentials (multi-tenant support).
     * Falls back to server credentials if botApiKey is null/empty.
     */
    public String placeOrderForBot(String side, String positionSide, BigDecimal quantity,
                                    boolean reduceOnly, String botApiKey, String botApiSecret,
                                    String targetSymbol) {
        if (botApiKey == null || botApiKey.isEmpty()) {
            return placeOrder(side, positionSide, quantity, reduceOnly);
        }
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", targetSymbol);
            params.put("side", side);
            if (hedgeMode) params.put("positionSide", positionSide);
            params.put("type", "MARKET");
            params.put("quantity", quantity.setScale(quantityPrecision, RoundingMode.DOWN).toPlainString());
            if (reduceOnly) params.put("reduceOnly", "true");

            String query = buildSignedQueryWithCredentials(params, botApiSecret);
            String response = webClient.post()
                    .uri("/fapi/v1/order?" + query)
                    .header("X-MBX-APIKEY", botApiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("[Bot] Order placed ({}): {}", targetSymbol, response);
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("[Bot] Error placing {} order for {}: {}", side, targetSymbol, e.getMessage());
            return null;
        }
    }

    public String placeBuyOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym) {
        return placeOrderForBot("BUY", "LONG", quantity, false, apiKey, apiSecret, sym);
    }

    public String placeSellOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym) {
        return placeOrderForBot("SELL", "LONG", quantity, true, apiKey, apiSecret, sym);
    }

    public String placeShortSellOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym) {
        return placeOrderForBot("SELL", "SHORT", quantity, false, apiKey, apiSecret, sym);
    }

    public String placeShortBuyOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym) {
        return placeOrderForBot("BUY", "SHORT", quantity, true, apiKey, apiSecret, sym);
    }

    // ============== PER-BOT BALANCE, CONDITIONAL ORDERS, CANCEL ==============

    public BigDecimal getBalanceForBot(String asset, String botApiKey, String botApiSecret) {
        if (botApiKey == null || botApiKey.isEmpty()) {
            return getBalance(asset);
        }
        try {
            String query = buildSignedQueryWithCredentials(new LinkedHashMap<>(), botApiSecret);
            String url = "/fapi/v2/balance?" + query;
            String response = webClient.get()
                    .uri(url)
                    .header("X-MBX-APIKEY", botApiKey)
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
            logger.error("[Bot] Error getting balance: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    public String placeStopLossOrderForBot(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice,
                                              String botApiKey, String botApiSecret, String targetSymbol) {
        String orderId = placeConditionalOrderForBot(side, positionSide, quantity, stopPrice, "STOP_MARKET", botApiKey, botApiSecret, targetSymbol);
        if (orderId == null) {
            orderId = placeConditionalOrderForBot(side, positionSide, quantity, stopPrice, "STOP", botApiKey, botApiSecret, targetSymbol);
        }
        return orderId;
    }

    public String placeTakeProfitOrderForBot(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice,
                                                String botApiKey, String botApiSecret, String targetSymbol) {
        String orderId = placeConditionalOrderForBot(side, positionSide, quantity, stopPrice, "TAKE_PROFIT_MARKET", botApiKey, botApiSecret, targetSymbol);
        if (orderId == null) {
            orderId = placeConditionalOrderForBot(side, positionSide, quantity, stopPrice, "TAKE_PROFIT", botApiKey, botApiSecret, targetSymbol);
        }
        return orderId;
    }

    private String placeConditionalOrderForBot(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice,
                                                String type, String botApiKey, String botApiSecret, String targetSymbol) {
        if (botApiKey == null || botApiKey.isEmpty()) {
            return placeConditionalOrder(side, positionSide, quantity, stopPrice, type);
        }
        AtomicReference<String> errorBodyRef = new AtomicReference<>();
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", targetSymbol);
            params.put("side", side);
            if (hedgeMode) {
                params.put("positionSide", positionSide);
            }
            params.put("type", type);
            params.put("quantity", quantity.setScale(quantityPrecision, RoundingMode.DOWN).toPlainString());
            params.put("reduceOnly", "true");
            params.put("stopPrice", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());

            if ("STOP".equals(type) || "TAKE_PROFIT".equals(type)) {
                params.put("price", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());
                params.put("timeInForce", "GTC");
            }

            String query = buildSignedQueryWithCredentials(params, botApiSecret);
            String response = webClient.post()
                    .uri("/fapi/v1/order?" + query)
                    .header("X-MBX-APIKEY", botApiKey)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body -> {
                            errorBodyRef.set(body);
                            logger.error("[Bot] Binance 4xx placing {} ({}): {}", type, clientResponse.statusCode(), body);
                            if (body.contains("-4120") || body.contains("not supported") || body.contains("Algo Order API")) {
                                logger.warn("[Bot] Order type {} not supported on /fapi/v1/order, will try /fapi/v1/algoOrder", type);
                            }
                        }).then(clientResponse.createException())
                    )
                    .onStatus(status -> status.is5xxServerError(), clientResponse ->
                        clientResponse.bodyToMono(String.class).doOnNext(body -> {
                            errorBodyRef.set(body);
                            logger.error("[Bot] Binance 5xx placing {} ({}): {}", type, clientResponse.statusCode(), body);
                        }).then(clientResponse.createException())
                    )
                    .bodyToMono(String.class)
                    .block();

            logger.info("[Bot] {} order placed: {}", type, response);
            return extractOrderId(response);
        } catch (Exception e) {
            String errorDetails = errorBodyRef.get();
            if (errorDetails == null) {
                errorDetails = e.getMessage();
            }
            if (errorDetails != null && (errorDetails.contains("-4120") || errorDetails.contains("not supported") || errorDetails.contains("Algo Order API"))) {
                logger.warn("[Bot] Order type {} not supported on /fapi/v1/order, falling back to /fapi/v1/algoOrder: {}", type, errorDetails);
                return placeConditionalOrderViaAlgoForBot(side, positionSide, quantity, stopPrice, type, botApiKey, botApiSecret, targetSymbol);
            }
            logger.error("[Bot] Error placing {} order: {}", type, e.getMessage(), e);
            return null;
        }
    }

    private String placeConditionalOrderViaAlgoForBot(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice,
                                                       String type, String botApiKey, String botApiSecret, String targetSymbol) {
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", targetSymbol);
            params.put("side", side);
            if (hedgeMode) {
                params.put("positionSide", positionSide);
            }
            params.put("algotype", "CONDITIONAL");
            params.put("orderType", type);
            params.put("quantity", quantity.setScale(quantityPrecision, RoundingMode.DOWN).toPlainString());
            params.put("reduceOnly", "true");
            params.put("triggerPrice", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());

            if ("STOP".equals(type) || "TAKE_PROFIT".equals(type)) {
                params.put("price", stopPrice.setScale(8, RoundingMode.HALF_UP).toPlainString());
                params.put("timeInForce", "GTC");
            }

            String query = buildSignedQueryWithCredentials(params, botApiSecret);
            String response = webClient.post()
                    .uri("/fapi/v1/algoOrder?" + query)
                    .header("X-MBX-APIKEY", botApiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("[Bot] {} algo order placed: {}", type, response);
            return extractAlgoId(response);
        } catch (Exception e) {
            logger.error("[Bot] Error placing {} algo order: {}", type, e.getMessage(), e);
            return null;
        }
    }

    public boolean cancelOrderForBot(String orderId, String botApiKey, String botApiSecret, String targetSymbol) {
        if (botApiKey == null || botApiKey.isEmpty()) {
            return cancelOrder(orderId);
        }
        if (orderId != null && orderId.startsWith("TESTNET_")) {
            logger.info("[Bot] TESTNET: Skipping cancel for dummy order {}", orderId);
            return true;
        }
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", targetSymbol);
            params.put("orderId", orderId);
            String query = buildSignedQueryWithCredentials(params, botApiSecret);

            webClient.delete()
                    .uri("/fapi/v1/order?" + query)
                    .header("X-MBX-APIKEY", botApiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("[Bot] Order {} cancelled.", orderId);
            return true;
        } catch (Exception e) {
            logger.warn("[Bot] Failed to cancel order {} via /fapi/v1/order: {}, trying /fapi/v1/algoOrder", orderId, e.getMessage());
            return cancelAlgoOrderForBot(orderId, botApiKey, botApiSecret, targetSymbol);
        }
    }

    private boolean cancelAlgoOrderForBot(String orderId, String botApiKey, String botApiSecret, String targetSymbol) {
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", targetSymbol);
            params.put("algoId", orderId);
            params.put("algotype", "CONDITIONAL");
            String query = buildSignedQueryWithCredentials(params, botApiSecret);

            webClient.delete()
                    .uri("/fapi/v1/algoOrder?" + query)
                    .header("X-MBX-APIKEY", botApiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("[Bot] Algo order {} cancelled.", orderId);
            algoOrderTypes.remove(orderId);
            return true;
        } catch (Exception e) {
            logger.error("[Bot] Error cancelling algo order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    // ============== PRIVATE HELPERS ==============

    private String buildSignedQuery(LinkedHashMap<String, Object> params) {
        return buildSignedQueryWithCredentials(params, apiSecret);
    }

    private String buildSignedQueryWithCredentials(LinkedHashMap<String, Object> params, String secret) {
        params.put("timestamp", System.currentTimeMillis());
        StringBuilder query = new StringBuilder();
        params.forEach((k, v) -> {
            if (query.length() > 0) query.append("&");
            query.append(k).append("=").append(v);
        });
        String signature = hmacSha256(query.toString(), secret);
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

    private String extractAlgoId(String jsonResponse) {
        try {
            JsonNode root = mapper.readTree(jsonResponse);
            return root.get("algoId").asText();
        } catch (Exception e) {
            logger.error("Failed to extract algoId from response: {}", jsonResponse);
            return "ALGO_" + System.currentTimeMillis();
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
