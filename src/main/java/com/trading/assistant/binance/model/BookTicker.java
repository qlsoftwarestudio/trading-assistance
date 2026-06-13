package com.trading.assistant.binance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Best bid/ask from Binance book ticker endpoint.
 * Used for spread calculation in scalping strategy.
 */
public class BookTicker {

    private String symbol;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;

    public BookTicker() {}

    public BookTicker(String symbol, BigDecimal bidPrice, BigDecimal askPrice) {
        this.symbol = symbol;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
    }

    /**
     * Spread as percentage: (ask - bid) / midPrice * 100
     */
    public double getSpreadPct() {
        if (bidPrice == null || askPrice == null || bidPrice.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }
        BigDecimal spread = askPrice.subtract(bidPrice);
        BigDecimal mid = bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        return spread.divide(mid, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    public BigDecimal getMidPrice() {
        if (bidPrice == null || askPrice == null) {
            return BigDecimal.ZERO;
        }
        return bidPrice.add(askPrice).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getBidPrice() { return bidPrice; }
    public void setBidPrice(BigDecimal bidPrice) { this.bidPrice = bidPrice; }
    public BigDecimal getAskPrice() { return askPrice; }
    public void setAskPrice(BigDecimal askPrice) { this.askPrice = askPrice; }

    @Override
    public String toString() {
        return String.format("BookTicker{symbol=%s, bid=%s, ask=%s, spread=%.3f%%}",
                symbol, bidPrice, askPrice, getSpreadPct());
    }
}
