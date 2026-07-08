package com.trading.assistant.binance;

import com.trading.assistant.binance.model.Kline;
import java.math.BigDecimal;
import java.util.List;

/**
 * Common interface for all exchange clients (Binance, BingX, etc.).
 * All strategy and execution components depend on this abstraction,
 * enabling exchange migration without touching business logic.
 *
 * Phase 2: global credentials (Binance or BingX, configured via EXCHANGE_ACTIVE).
 * Phase 4: per-user credentials supported via the *ForBot methods.
 */
public interface ExchangeClient {

    boolean isConfigured();

    // ── Market data ─────────────────────────────────────────────────────────────
    BigDecimal getBalance(String asset);
    BigDecimal getPrice(String targetSymbol);
    List<Kline> getKlines(String targetSymbol, String interval, int limit);

    // ── Leverage ─────────────────────────────────────────────────────────────────
    void setLeverageForSymbol(String targetSymbol, int leverage);
    void setLeverageForBot(String targetSymbol, int leverage, String apiKey, String apiSecret);

    // ── Orders (global credentials) ──────────────────────────────────────────────
    String placeBuyOrder(BigDecimal quantity);
    String placeShortSellOrder(BigDecimal quantity);

    String placeBuyOrderForSymbol(String sym, BigDecimal quantity);
    String placeShortSellOrderForSymbol(String sym, BigDecimal quantity);
    String placeSellOrderForSymbol(String sym, BigDecimal quantity);
    String placeShortBuyOrderForSymbol(String sym, BigDecimal quantity);

    String placeStopLossOrderForSymbol(String side, String positionSide,
                                       BigDecimal quantity, BigDecimal stopPrice, String sym);
    String placeTakeProfitOrderForSymbol(String side, String positionSide,
                                         BigDecimal quantity, BigDecimal stopPrice, String sym);

    boolean cancelOrder(String orderId);

    // Spread check (Hunter / scalp gate)
    double getSpreadPct(String symbol);

    // Legacy single-symbol conditional orders (used in trailing SL replace logic)
    String placeStopLossOrder(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice);
    String placeTakeProfitOrder(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice);

    // ── Utility ──────────────────────────────────────────────────────────────────
    BigDecimal roundQuantityForSymbol(String sym, BigDecimal quantity);

    // ── Per-bot credentials (Phase 4: fully per-user; Phase 2: BingX uses global) ─
    BigDecimal getBalanceForBot(String asset, String apiKey, String apiSecret);
    String placeBuyOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym);
    String placeShortSellOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym);
    String placeSellOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym);
    String placeShortBuyOrderForBot(BigDecimal quantity, String apiKey, String apiSecret, String sym);
    String placeStopLossOrderForBot(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice, String apiKey, String apiSecret, String sym);
    String placeTakeProfitOrderForBot(String side, String positionSide, BigDecimal quantity, BigDecimal stopPrice, String apiKey, String apiSecret, String sym);
    boolean cancelOrderForBot(String orderId, String apiKey, String apiSecret, String sym);
}
