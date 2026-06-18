package com.trading.assistant.binance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.assistant.execution.TradeManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;

@Service
public class BinanceUserDataStreamClient {

    private static final Logger logger = LoggerFactory.getLogger(BinanceUserDataStreamClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final int KEEP_ALIVE_INTERVAL_MIN = 30;
    private static final int RECONNECT_DELAY_SECONDS = 5;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 300;

    @Value("${binance.api.base-url:https://testnet.binancefuture.com}")
    private String binanceBaseUrl;

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private TradeManager tradeManager;

    private HttpClient httpClient;
    private WebSocket webSocket;
    private String listenKey;
    private ScheduledExecutorService executorService;
    private volatile boolean running = true;
    private int reconnectAttempts = 0;

    @PostConstruct
    public void init() {
        if (!binanceClient.isConfigured()) {
            logger.warn("Binance not configured. User Data Stream will not start.");
            return;
        }
        httpClient = HttpClient.newHttpClient();
        executorService = Executors.newScheduledThreadPool(2);
        startStream();
    }

    private String resolveWsBaseUrl() {
        if (binanceBaseUrl != null && (binanceBaseUrl.contains("testnet") || binanceBaseUrl.contains("demo-fapi"))) {
            return "wss://stream.binancefuture.com/ws";
        }
        return "wss://fstream.binance.com/ws";
    }

    private void startStream() {
        try {
            listenKey = binanceClient.createListenKey();
            if (listenKey == null || listenKey.isEmpty()) {
                logger.error("Failed to create listen key. Retrying in {}s", RECONNECT_DELAY_SECONDS);
                scheduleReconnect(RECONNECT_DELAY_SECONDS);
                return;
            }
            String wsBase = resolveWsBaseUrl();
            logger.info("Listen key created. Connecting to User Data Stream: {}...", wsBase);

            String wsUrl = wsBase + "/" + listenKey;
            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket ws) {
                    logger.info("✅ User Data Stream connected.");
                    reconnectAttempts = 0;
                    WebSocket.Listener.super.onOpen(ws);
                }

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    String message = data.toString();
                    if (last) {
                        handleMessage(message);
                    }
                    return WebSocket.Listener.super.onText(ws, data, last);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    logger.warn("⚠️ User Data Stream closed. Status: {}, Reason: {}", statusCode, reason);
                    if (running) {
                        scheduleReconnect(RECONNECT_DELAY_SECONDS);
                    }
                    return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    logger.error("❌ User Data Stream error: {}", error.getMessage(), error);
                    if (running) {
                        scheduleReconnect(RECONNECT_DELAY_SECONDS);
                    }
                }
            };

            webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), listener)
                    .join();

            // Schedule keep-alive
            executorService.scheduleAtFixedRate(
                    this::keepAlive,
                    KEEP_ALIVE_INTERVAL_MIN,
                    KEEP_ALIVE_INTERVAL_MIN,
                    TimeUnit.MINUTES
            );

        } catch (Exception e) {
            logger.error("Error starting User Data Stream: {}", e.getMessage(), e);
            scheduleReconnect(RECONNECT_DELAY_SECONDS);
        }
    }

    private void keepAlive() {
        try {
            if (listenKey != null && !listenKey.isEmpty()) {
                boolean ok = binanceClient.keepAliveListenKey(listenKey);
                if (ok) {
                    logger.debug("Listen key keep-alive sent.");
                } else {
                    logger.warn("Keep-alive failed. Will reconnect.");
                    reconnect();
                }
            }
        } catch (Exception e) {
            logger.error("Error during keep-alive: {}", e.getMessage());
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode root = mapper.readTree(message);
            String eventType = root.has("e") ? root.get("e").asText() : "";

            if ("ORDER_TRADE_UPDATE".equals(eventType)) {
                JsonNode order = root.get("o");
                if (order != null) {
                    String orderType = order.has("o") ? order.get("o").asText() : "";
                    String status = order.has("X") ? order.get("X").asText() : "";
                    String orderId = order.has("i") ? order.get("i").asText() : "";
                    String avgPrice = order.has("ap") ? order.get("ap").asText() : "0";

                    logger.info("📡 WS ORDER_TRADE_UPDATE: type={}, status={}, orderId={}, avgPrice={}",
                            orderType, status, orderId, avgPrice);

                    if ("FILLED".equals(status) || "PARTIALLY_FILLED".equals(status)) {
                        tradeManager.handleOrderUpdate(orderId, orderType, status, avgPrice);
                    }
                }
            } else if ("listenKeyExpired".equals(eventType)) {
                logger.warn("⚠️ Listen key expired. Reconnecting...");
                reconnect();
            }
        } catch (Exception e) {
            logger.error("Error parsing WS message: {}", e.getMessage(), e);
        }
    }

    private void scheduleReconnect(int delaySeconds) {
        if (!running) return;
        int delay = Math.min(delaySeconds * (1 << reconnectAttempts), MAX_RECONNECT_DELAY_SECONDS);
        reconnectAttempts++;
        logger.info("Scheduling reconnect in {}s (attempt {})...", delay, reconnectAttempts);
        executorService.schedule(this::reconnect, delay, TimeUnit.SECONDS);
    }

    private void reconnect() {
        try {
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Reconnecting").join();
                } catch (Exception ignored) {
                }
                webSocket = null;
            }
            startStream();
        } catch (Exception e) {
            logger.error("Error during reconnect: {}", e.getMessage(), e);
            scheduleReconnect(RECONNECT_DELAY_SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down").join();
            } catch (Exception ignored) {
            }
        }
        logger.info("User Data Stream client shutdown.");
    }
}
