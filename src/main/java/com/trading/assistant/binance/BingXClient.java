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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BingX Perpetual Futures client implementing the common ExchangeClient interface.
 *
 * API reference: https://bingx-api.github.io/docs/#/en-us/swapV2/
 * Symbol format: SOL-USDT (BingX) ← converts from SOLUSDT (internal)
 *
 * Phase 2: global credentials only (BINGX_API_KEY / BINGX_SECRET_KEY).
 * Phase 4: per-user credential support in *ForBot methods.
 *
 * NOTE: verify exact endpoint paths and response shapes against live BingX docs
 * if the exchange updates its API.
 */
@Service("bingXClient")
public class BingXClient implements ExchangeClient {

    private static final Logger logger = LoggerFactory.getLogger(BingXClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${bingx.api.key:}")
    private String apiKey;

    @Value("${bingx.api.secret:}")
    private String apiSecret;

    @Value("${bingx.api.base-url:https://open-api.bingx.com}")
    private String baseUrl;

    @Value("${trading.strategy.leverage:7}")
    private int defaultLeverage;

    @Value("${trading.strategy.symbols:}")
    private List<String> configuredSymbols;

    @Value("${bingx.position-mode:one-way}")
    private String positionMode;

    private WebClient webClient;
    private boolean configured = false;
    private final Map<String, Integer> symbolQuantityPrecision = new ConcurrentHashMap<>();

    private boolean isHedgeMode() {
        return "hedge".equalsIgnoreCase(positionMode);
    }

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();

        if (apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty()) {
            this.configured = true;
            logger.info("BingX client configured ({})", baseUrl);
            fetchContractInfo();
            // BingX supports dual positions (LONG/SHORT simultaneously) natively via positionSide in each order.
            // No account-level hedge mode endpoint is needed.
        } else {
            logger.warn("BingX API keys not configured. BingXClient in demo mode.");
        }
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    // ── Symbol format conversion ─────────────────────────────────────────────────

    // BingX perpetual-swap tickers for FX indices use the NCFX*2* prefix (NCFXEUR2USD-USDT, etc.)
    private static final Map<String, String> FX_SYMBOL_MAP = Map.of(
            "EURUSD", "NCFXEUR2USD-USDT",
            "GBPUSD", "NCFXGBP2USD-USDT",
            "USDJPY", "NCFXUSD2JPY-USDT",
            "EURJPY", "NCFXEUR2JPY-USDT",
            "EURGBP", "NCFXEUR2GBP-USDT",
            "GBPJPY", "NCFXGBP2JPY-USDT",
            "AUDUSD", "NCFXAUD2USD-USDT",
            "USDCAD", "NCFXUSD2CAD-USDT",
            "USDCHF", "NCFXUSD2CHF-USDT",
            "NZDUSD", "NCFXNZD2USD-USDT"
    );

    /**
     * Converts internal symbol (SOLUSDT) to BingX format (SOL-USDT).
     * FX pairs (EURUSD, GBPUSD, USDJPY, ...) are mapped to BingX's NCFX*2*-USDT tickers.
     */
    private String toBingXSymbol(String symbol) {
        if (symbol == null) return symbol;
        String upper = symbol.toUpperCase();
        if (FX_SYMBOL_MAP.containsKey(upper)) {
            return FX_SYMBOL_MAP.get(upper);
        }
        if (upper.endsWith("USDT")) return upper.substring(0, upper.length() - 4) + "-USDT";
        if (upper.endsWith("USDC")) return upper.substring(0, upper.length() - 4) + "-USDC";
        if (upper.endsWith("BTC"))  return upper.substring(0, upper.length() - 3) + "-BTC";
        return upper;
    }

    // ── Contract info ────────────────────────────────────────────────────────────

    private void fetchContractInfo() {
        try {
            String response = webClient.get()
                    .uri("/openApi/swap/v2/quote/contracts")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            if (root.has("data") && root.get("data").isArray()) {
                for (JsonNode contract : root.get("data")) {
                    String sym = contract.has("symbol") ? contract.get("symbol").asText() : null;
                    int qtyScale = contract.has("quantityPrecision") ? contract.get("quantityPrecision").asInt() : 2;
                    if (sym != null) symbolQuantityPrecision.put(sym, qtyScale);
                }
                logger.info("BingX: loaded {} contracts", symbolQuantityPrecision.size());
                verifyConfiguredSymbols();
            }
        } catch (Exception e) {
            logger.warn("BingX: could not fetch contract info: {}. Using default precision=2", e.getMessage());
        }
    }

    /**
     * Logs whether each configured trading symbol is actually listed on BingX (as a swap contract).
     * A MISSING symbol means BingX does not offer that perpetual — orders/klines for it will fail.
     */
    private void verifyConfiguredSymbols() {
        if (configuredSymbols == null || configuredSymbols.isEmpty()) {
            logger.warn("BingX: no trading.strategy.symbols configured to verify");
            return;
        }
        for (String internal : configuredSymbols) {
            if (internal == null || internal.isBlank()) continue;
            String bxSym = toBingXSymbol(internal.trim());
            if (symbolQuantityPrecision.containsKey(bxSym)) {
                logger.info("✅ BingX symbol available: {} → {} (qtyPrecision={})",
                        internal.trim(), bxSym, symbolQuantityPrecision.get(bxSym));
            } else {
                logger.error("❌ BingX symbol NOT LISTED: {} → {} — this pair is not offered as a swap; trading it will fail",
                        internal.trim(), bxSym);
            }
        }
    }

    // ── Market data ──────────────────────────────────────────────────────────────

    @Override
    public BigDecimal getBalance(String asset) {
        if (!configured) {
            logger.warn("BingX demo mode: returning demo balance 1000 USDT");
            return new BigDecimal("1000.00");
        }
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            String query = buildSignedQuery(params);
            String response = webClient.get()
                    .uri("/openApi/swap/v2/user/balance?" + query)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            if (root.path("code").asInt() == 0) {
                JsonNode balance = root.path("data").path("balance");
                // Use equity (total balance + unrealized PnL) for portfolio display
                String equity = balance.path("equity").asText("0");
                return new BigDecimal(equity);
            }
            logger.warn("BingX getBalance unexpected response: {}", response);
            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.error("BingX getBalance error: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public BigDecimal getPrice(String targetSymbol) {
        if (!configured) return new BigDecimal("100.00");
        try {
            String bxSym = toBingXSymbol(targetSymbol);
            String response = webClient.get()
                    .uri("/openApi/swap/v2/quote/price?symbol=" + bxSym)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            if (root.path("code").asInt() == 0) {
                return new BigDecimal(root.path("data").path("price").asText("0"));
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            logger.error("BingX getPrice error for {}: {}", targetSymbol, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public List<Kline> getKlines(String targetSymbol, String interval, int limit) {
        if (!configured) {
            logger.info("BingX demo mode: generating simulated klines");
            return generateDemoKlines(limit);
        }
        try {
            String bxSym = toBingXSymbol(targetSymbol);
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/openApi/swap/v3/quote/klines")
                            .queryParam("symbol", bxSym)
                            .queryParam("interval", interval)
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            JsonNode root = mapper.readTree(response);
            List<Kline> klines = new ArrayList<>();
            if (root.path("code").asInt() == 0 && root.has("data")) {
                for (JsonNode k : root.get("data")) {
                    klines.add(new Kline(
                            k.path("time").asLong(),
                            new BigDecimal(k.path("open").asText("0")),
                            new BigDecimal(k.path("high").asText("0")),
                            new BigDecimal(k.path("low").asText("0")),
                            new BigDecimal(k.path("close").asText("0")),
                            new BigDecimal(k.path("volume").asText("0"))
                    ));
                }
            }
            return klines;
        } catch (Exception e) {
            logger.error("BingX getKlines error for {}: {}", targetSymbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── Leverage ─────────────────────────────────────────────────────────────────

    @Override
    public void setLeverageForSymbol(String targetSymbol, int leverage) {
        if (!configured) return;
        try {
            String bxSym = toBingXSymbol(targetSymbol);
            if (isHedgeMode()) {
                // Hedge mode: set leverage per side
                for (String side : new String[]{"LONG", "SHORT"}) {
                    LinkedHashMap<String, Object> params = new LinkedHashMap<>();
                    params.put("symbol", bxSym);
                    params.put("side", side);
                    params.put("leverage", leverage);
                    String query = buildSignedQuery(params);
                    webClient.post()
                            .uri("/openApi/swap/v2/trade/leverage?" + query)
                            .header("X-BX-APIKEY", apiKey)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block();
                }
            } else {
                // One-way mode: no side parameter
                LinkedHashMap<String, Object> params = new LinkedHashMap<>();
                params.put("symbol", bxSym);
                params.put("leverage", leverage);
                String query = buildSignedQuery(params);
                webClient.post()
                        .uri("/openApi/swap/v2/trade/leverage?" + query)
                        .header("X-BX-APIKEY", apiKey)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();
            }
            logger.info("BingX: leverage set to {}x for {} (mode={})", leverage, bxSym, positionMode);
        } catch (Exception e) {
            logger.error("BingX setLeverage error for {}: {}", targetSymbol, e.getMessage());
        }
    }

    @Override
    public void setLeverageForBot(String targetSymbol, int leverage, String apiKey, String apiSecret) {
        setLeverageForSymbol(targetSymbol, leverage);
    }

    // ── Orders ───────────────────────────────────────────────────────────────────

    private String placeMarketOrder(String sym, String side, String positionSide, BigDecimal quantity, boolean reduceOnly) {
        if (!configured) {
            logger.info("BingX demo: {} {} {} qty={}", side, positionSide, sym, quantity);
            return "BINGX_DEMO_" + System.currentTimeMillis();
        }
        try {
            String bxSym = toBingXSymbol(sym);
            int prec = symbolQuantityPrecision.getOrDefault(bxSym, 2);
            BigDecimal qty = quantity.setScale(prec, RoundingMode.DOWN);

            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", bxSym);
            params.put("side", side);
            if (isHedgeMode()) {
                params.put("positionSide", positionSide);
            }
            params.put("type", "MARKET");
            params.put("quantity", qty.toPlainString());
            if (reduceOnly) params.put("reduceOnly", "true");

            String query = buildSignedQuery(params);
            String response = webClient.post()
                    .uri("/openApi/swap/v2/trade/order?" + query)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("BingX order placed: {}", response);
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("BingX placeMarketOrder error: {}", e.getMessage());
            return null;
        }
    }

    private String placeConditionalOrder(String sym, String side, String positionSide,
                                         BigDecimal quantity, BigDecimal stopPrice, String type) {
        if (!configured) return "BINGX_DEMO_COND_" + System.currentTimeMillis();
        try {
            String bxSym = toBingXSymbol(sym);
            int prec = symbolQuantityPrecision.getOrDefault(bxSym, 2);
            BigDecimal qty = quantity.setScale(prec, RoundingMode.DOWN);

            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", bxSym);
            params.put("side", side);
            if (isHedgeMode()) {
                params.put("positionSide", positionSide);
            }
            params.put("type", type);
            params.put("quantity", qty.toPlainString());
            params.put("stopPrice", stopPrice.toPlainString());
            params.put("workingType", "MARK_PRICE");
            if (!isHedgeMode()) {
                // One-way mode: SL/TP must reduce the existing position
                params.put("reduceOnly", "true");
            }

            String query = buildSignedQuery(params);
            String response = webClient.post()
                    .uri("/openApi/swap/v2/trade/order?" + query)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            logger.info("BingX conditional order placed: {}", response);
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("BingX placeConditionalOrder error ({}): {}", type, e.getMessage());
            return null;
        }
    }

    @Override
    public String placeBuyOrderForSymbol(String sym, BigDecimal quantity) {
        return placeMarketOrder(sym, "BUY", "LONG", quantity, false);
    }

    @Override
    public String placeShortSellOrderForSymbol(String sym, BigDecimal quantity) {
        return placeMarketOrder(sym, "SELL", "SHORT", quantity, false);
    }

    @Override
    public String placeSellOrderForSymbol(String sym, BigDecimal quantity) {
        return placeMarketOrder(sym, "SELL", "LONG", quantity, true);
    }

    @Override
    public String placeShortBuyOrderForSymbol(String sym, BigDecimal quantity) {
        return placeMarketOrder(sym, "BUY", "SHORT", quantity, true);
    }

    @Override
    public String placeStopLossOrderForSymbol(String side, String positionSide,
                                              BigDecimal quantity, BigDecimal stopPrice, String sym) {
        return placeConditionalOrder(sym, side, positionSide, quantity, stopPrice, "STOP_MARKET");
    }

    @Override
    public String placeTakeProfitOrderForSymbol(String side, String positionSide,
                                                BigDecimal quantity, BigDecimal stopPrice, String sym) {
        return placeConditionalOrder(sym, side, positionSide, quantity, stopPrice, "TAKE_PROFIT_MARKET");
    }

    @Override
    public boolean cancelOrder(String orderId, String symbol) {
        if (!configured) return true;
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", toBingXSymbol(symbol));
            params.put("orderId", orderId);
            String query = buildSignedQuery(params);
            webClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/openApi/swap/v2/trade/order?" + query)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            logger.error("BingX cancelOrder error for {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────────

    @Override
    public BigDecimal roundQuantityForSymbol(String sym, BigDecimal quantity) {
        String bxSym = toBingXSymbol(sym);
        int prec = symbolQuantityPrecision.getOrDefault(bxSym, 2);
        return quantity.setScale(prec, RoundingMode.DOWN);
    }

    @Override
    public double getSpreadPct(String sym) {
        if (!configured) return 0.01;
        try {
            String bxSym = toBingXSymbol(sym != null ? sym : "SOLUSDT");
            String response = webClient.get()
                    .uri("/openApi/swap/v2/quote/bookTicker?symbol=" + bxSym)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(response);
            if (root.path("code").asInt() == 0) {
                BigDecimal bid = new BigDecimal(root.path("data").path("bidPrice").asText("0"));
                BigDecimal ask = new BigDecimal(root.path("data").path("askPrice").asText("0"));
                if (bid.compareTo(BigDecimal.ZERO) > 0) {
                    return ask.subtract(bid).divide(bid, 8, RoundingMode.HALF_UP).doubleValue() * 100.0;
                }
            }
        } catch (Exception e) {
            logger.warn("BingX getSpreadPct error for {}: {}", sym, e.getMessage());
        }
        return 999.0;
    }

    // ── Per-bot credentials (Phase 4: actual per-user signing) ──────────────────

    @Override
    public BigDecimal getBalanceForBot(String asset, String botKey, String botSecret) {
        if (botKey == null || botKey.isEmpty()) return getBalance(asset);
        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            String query = buildSignedQueryWith(params, botSecret);
            String response = webClient.get()
                    .uri("/openApi/swap/v2/user/balance?" + query)
                    .header("X-BX-APIKEY", botKey)
                    .retrieve().bodyToMono(String.class).block();
            JsonNode root = mapper.readTree(response);
            if (root.path("code").asInt() == 0) {
                return new BigDecimal(root.path("data").path("balance").path("equity").asText("0"));
            }
            logger.warn("BingX getBalanceForBot non-zero code: {}", response);
            return getBalance(asset);
        } catch (Exception e) {
            logger.error("BingX getBalanceForBot error: {}", e.getMessage());
            return getBalance(asset);
        }
    }

    @Override
    public String placeBuyOrderForBot(BigDecimal quantity, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return placeBuyOrderForSymbol(sym, quantity);
        return placeMarketOrderWith(sym, "BUY", "LONG", quantity, false, botKey, botSecret);
    }

    @Override
    public String placeShortSellOrderForBot(BigDecimal quantity, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return placeShortSellOrderForSymbol(sym, quantity);
        return placeMarketOrderWith(sym, "SELL", "SHORT", quantity, false, botKey, botSecret);
    }

    @Override
    public String placeSellOrderForBot(BigDecimal quantity, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return placeSellOrderForSymbol(sym, quantity);
        return placeMarketOrderWith(sym, "SELL", "LONG", quantity, true, botKey, botSecret);
    }

    @Override
    public String placeShortBuyOrderForBot(BigDecimal quantity, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return placeShortBuyOrderForSymbol(sym, quantity);
        return placeMarketOrderWith(sym, "BUY", "SHORT", quantity, true, botKey, botSecret);
    }

    @Override
    public String placeStopLossOrderForBot(String side, String positionSide, BigDecimal quantity,
                                           BigDecimal stopPrice, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return placeStopLossOrderForSymbol(side, positionSide, quantity, stopPrice, sym);
        return placeConditionalOrderWith(sym, side, positionSide, quantity, stopPrice, "STOP_MARKET", botKey, botSecret);
    }

    @Override
    public String placeTakeProfitOrderForBot(String side, String positionSide, BigDecimal quantity,
                                             BigDecimal stopPrice, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return placeTakeProfitOrderForSymbol(side, positionSide, quantity, stopPrice, sym);
        return placeConditionalOrderWith(sym, side, positionSide, quantity, stopPrice, "TAKE_PROFIT_MARKET", botKey, botSecret);
    }

    @Override
    public boolean cancelOrderForBot(String orderId, String botKey, String botSecret, String sym) {
        if (botKey == null || botKey.isEmpty()) return cancelOrder(orderId, sym);
        try {
            String bxSym = toBingXSymbol(sym);
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("orderId", orderId);
            params.put("symbol", bxSym);
            String query = buildSignedQueryWith(params, botSecret);
            webClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/openApi/swap/v2/trade/order?" + query)
                    .header("X-BX-APIKEY", botKey)
                    .retrieve().bodyToMono(String.class).block();
            return true;
        } catch (Exception e) {
            logger.error("BingX cancelOrderForBot error: {}", e.getMessage());
            return false;
        }
    }

    private String placeMarketOrderWith(String sym, String side, String positionSide,
                                        BigDecimal quantity, boolean reduceOnly,
                                        String botKey, String botSecret) {
        try {
            String bxSym = toBingXSymbol(sym);
            int prec = symbolQuantityPrecision.getOrDefault(bxSym, 2);
            BigDecimal qty = quantity.setScale(prec, RoundingMode.DOWN);
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", bxSym);
            params.put("side", side);
            if (isHedgeMode()) {
                params.put("positionSide", positionSide);
            }
            params.put("type", "MARKET");
            params.put("quantity", qty.toPlainString());
            if (reduceOnly) params.put("reduceOnly", "true");
            String query = buildSignedQueryWith(params, botSecret);
            String response = webClient.post()
                    .uri("/openApi/swap/v2/trade/order?" + query)
                    .header("X-BX-APIKEY", botKey)
                    .retrieve().bodyToMono(String.class).block();
            logger.info("BingX bot order: {}", response);
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("BingX placeMarketOrderWith error: {}", e.getMessage());
            return null;
        }
    }

    private String placeConditionalOrderWith(String sym, String side, String positionSide,
                                             BigDecimal quantity, BigDecimal stopPrice, String type,
                                             String botKey, String botSecret) {
        try {
            String bxSym = toBingXSymbol(sym);
            int prec = symbolQuantityPrecision.getOrDefault(bxSym, 2);
            BigDecimal qty = quantity.setScale(prec, RoundingMode.DOWN);
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", bxSym);
            params.put("side", side);
            if (isHedgeMode()) {
                params.put("positionSide", positionSide);
            }
            params.put("type", type);
            params.put("quantity", qty.toPlainString());
            params.put("stopPrice", stopPrice.toPlainString());
            params.put("workingType", "MARK_PRICE");
            if (!isHedgeMode()) {
                params.put("reduceOnly", "true");
            }
            String query = buildSignedQueryWith(params, botSecret);
            String response = webClient.post()
                    .uri("/openApi/swap/v2/trade/order?" + query)
                    .header("X-BX-APIKEY", botKey)
                    .retrieve().bodyToMono(String.class).block();
            return extractOrderId(response);
        } catch (Exception e) {
            logger.error("BingX placeConditionalOrderWith error: {}", e.getMessage());
            return null;
        }
    }

    // ── Auth / signing ───────────────────────────────────────────────────────────

    private String buildSignedQuery(LinkedHashMap<String, Object> params) {
        return buildSignedQueryWith(params, apiSecret);
    }

    private String buildSignedQueryWith(LinkedHashMap<String, Object> params, String secret) {
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
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("BingX: failed to sign request", e);
        }
    }

    private String extractOrderId(String jsonResponse) {
        try {
            JsonNode root = mapper.readTree(jsonResponse);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                logger.error("BingX order rejected — code={}, msg={}, raw={}",
                        code, root.path("msg").asText(""), jsonResponse);
                return null;
            }
            JsonNode order = root.path("data").path("order");
            if (order.has("orderId")) return order.get("orderId").asText();
            if (root.path("data").has("orderId")) return root.path("data").get("orderId").asText();
            logger.error("BingX: orderId field not found in successful response: {}", jsonResponse);
        } catch (Exception e) {
            logger.error("BingX: failed to parse order response: {} | raw: {}", e.getMessage(), jsonResponse);
        }
        return null;
    }

    // ── Demo klines ──────────────────────────────────────────────────────────────

    private List<Kline> generateDemoKlines(int limit) {
        List<Kline> klines = new ArrayList<>();
        java.util.Random rng = new java.util.Random();
        BigDecimal base = new BigDecimal("150.00");
        long now = System.currentTimeMillis();
        long step = 5 * 60 * 1000L;
        for (int i = limit - 1; i >= 0; i--) {
            double chg = (rng.nextDouble() - 0.5) * 0.04;
            BigDecimal close = base.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(chg))).setScale(4, RoundingMode.HALF_UP);
            BigDecimal high  = close.multiply(BigDecimal.valueOf(1 + rng.nextDouble() * 0.005)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal low   = close.multiply(BigDecimal.valueOf(1 - rng.nextDouble() * 0.005)).setScale(4, RoundingMode.HALF_UP);
            BigDecimal open  = low.add(high.subtract(low).multiply(BigDecimal.valueOf(rng.nextDouble()))).setScale(4, RoundingMode.HALF_UP);
            BigDecimal vol   = new BigDecimal(rng.nextInt(10000) + 5000);
            klines.add(new Kline(now - i * step, open, high, low, close, vol));
            base = close;
        }
        return klines;
    }
}
