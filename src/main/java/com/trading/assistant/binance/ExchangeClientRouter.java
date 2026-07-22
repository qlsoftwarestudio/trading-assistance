package com.trading.assistant.binance;

import com.trading.assistant.binance.model.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;

/**
 * Routes all ExchangeClient calls to the currently active exchange
 * (Binance or BingX), as configured by EXCHANGE_ACTIVE env var.
 *
 * This is the @Primary ExchangeClient bean injected across the whole system.
 * Switching exchanges = changing EXCHANGE_ACTIVE in Railway. No code changes needed.
 */
@Primary
@Service
public class ExchangeClientRouter implements ExchangeClient {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeClientRouter.class);

    @Value("${exchange.active:binance}")
    private String activeExchange;

    private final ExchangeClient binanceClient;
    private final ExchangeClient bingXClient;
    private ExchangeClient active;

    public ExchangeClientRouter(
            @Qualifier("binanceClient") ExchangeClient binanceClient,
            @Qualifier("bingXClient")  ExchangeClient bingXClient) {
        this.binanceClient = binanceClient;
        this.bingXClient   = bingXClient;
    }

    @PostConstruct
    public void init() {
        if ("bingx".equalsIgnoreCase(activeExchange)) {
            this.active = bingXClient;
            logger.info("🔀 Exchange router: active=BingX (configured={})", bingXClient.isConfigured());
        } else {
            this.active = binanceClient;
            logger.info("🔀 Exchange router: active=Binance (configured={})", binanceClient.isConfigured());
        }
    }

    private ExchangeClient client() { return active; }

    @Override public boolean isConfigured()                                                      { return client().isConfigured(); }
    @Override public BigDecimal getBalance(String asset)                                         { return client().getBalance(asset); }
    @Override public BigDecimal getPrice(String sym)                                             { return client().getPrice(sym); }
    @Override public List<Kline> getKlines(String sym, String interval, int limit)               { return client().getKlines(sym, interval, limit); }
    @Override public void setLeverageForSymbol(String sym, int leverage)                         { client().setLeverageForSymbol(sym, leverage); }
    @Override public void setLeverageForBot(String sym, int lev, String k, String s)             { client().setLeverageForBot(sym, lev, k, s); }
    @Override public String placeBuyOrderForSymbol(String sym, BigDecimal qty)                   { return client().placeBuyOrderForSymbol(sym, qty); }
    @Override public String placeShortSellOrderForSymbol(String sym, BigDecimal qty)             { return client().placeShortSellOrderForSymbol(sym, qty); }
    @Override public String placeSellOrderForSymbol(String sym, BigDecimal qty)                  { return client().placeSellOrderForSymbol(sym, qty); }
    @Override public String placeShortBuyOrderForSymbol(String sym, BigDecimal qty)             { return client().placeShortBuyOrderForSymbol(sym, qty); }
    @Override public String placeStopLossOrderForSymbol(String side, String ps, BigDecimal qty, BigDecimal stop, String sym)   { return client().placeStopLossOrderForSymbol(side, ps, qty, stop, sym); }
    @Override public String placeTakeProfitOrderForSymbol(String side, String ps, BigDecimal qty, BigDecimal stop, String sym) { return client().placeTakeProfitOrderForSymbol(side, ps, qty, stop, sym); }
    @Override public boolean cancelOrder(String orderId, String symbol)                            { return client().cancelOrder(orderId, symbol); }
    @Override public double getSpreadPct(String symbol)                                            { return client().getSpreadPct(symbol); }
    @Override public BigDecimal roundQuantityForSymbol(String sym, BigDecimal qty)               { return client().roundQuantityForSymbol(sym, qty); }
    @Override public BigDecimal getBalanceForBot(String asset, String k, String s)               { return client().getBalanceForBot(asset, k, s); }
    @Override public String placeBuyOrderForBot(BigDecimal qty, String k, String s, String sym)  { return client().placeBuyOrderForBot(qty, k, s, sym); }
    @Override public String placeShortSellOrderForBot(BigDecimal qty, String k, String s, String sym) { return client().placeShortSellOrderForBot(qty, k, s, sym); }
    @Override public String placeSellOrderForBot(BigDecimal qty, String k, String s, String sym) { return client().placeSellOrderForBot(qty, k, s, sym); }
    @Override public String placeShortBuyOrderForBot(BigDecimal qty, String k, String s, String sym) { return client().placeShortBuyOrderForBot(qty, k, s, sym); }
    @Override public String placeStopLossOrderForBot(String side, String ps, BigDecimal qty, BigDecimal stop, String k, String s, String sym)  { return client().placeStopLossOrderForBot(side, ps, qty, stop, k, s, sym); }
    @Override public String placeTakeProfitOrderForBot(String side, String ps, BigDecimal qty, BigDecimal stop, String k, String s, String sym) { return client().placeTakeProfitOrderForBot(side, ps, qty, stop, k, s, sym); }
    @Override public boolean cancelOrderForBot(String id, String k, String s, String sym)        { return client().cancelOrderForBot(id, k, s, sym); }
}
