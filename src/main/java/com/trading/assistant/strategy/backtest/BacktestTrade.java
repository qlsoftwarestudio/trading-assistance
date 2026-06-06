package com.trading.assistant.strategy.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Trade simulado durante un backtest.
 */
public class BacktestTrade {

    private final String action; // LONG or SHORT
    private final BigDecimal entryPrice;
    private final BigDecimal exitPrice;
    private final BigDecimal quantity;
    private final String exitReason;
    private final long entryTimestamp;
    private final long exitTimestamp;
    private final BigDecimal pnl;

    public BacktestTrade(String action, BigDecimal entryPrice, BigDecimal quantity,
                         BigDecimal exitPrice, String exitReason, long entryTimestamp, long exitTimestamp) {
        this.action = action;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.exitPrice = exitPrice;
        this.exitReason = exitReason;
        this.entryTimestamp = entryTimestamp;
        this.exitTimestamp = exitTimestamp;

        if ("SHORT".equals(action)) {
            this.pnl = entryPrice.subtract(exitPrice).multiply(quantity);
        } else {
            this.pnl = exitPrice.subtract(entryPrice).multiply(quantity);
        }
    }

    public String getAction() { return action; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public BigDecimal getQuantity() { return quantity; }
    public String getExitReason() { return exitReason; }
    public long getEntryTimestamp() { return entryTimestamp; }
    public long getExitTimestamp() { return exitTimestamp; }
    public BigDecimal getPnl() { return pnl; }

    public double getPnlPercent() {
        if (entryPrice.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return pnl.divide(entryPrice.multiply(quantity), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }
}
