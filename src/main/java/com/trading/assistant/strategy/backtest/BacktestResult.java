package com.trading.assistant.strategy.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Resultado de una simulacion de backtesting.
 */
public class BacktestResult {

    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final BigDecimal totalPnl;
    private final BigDecimal grossProfit;
    private final BigDecimal grossLoss;
    private final double winRate;
    private final double profitFactor;
    private final double maxDrawdownPct;
    private final double sharpeRatio;
    private final List<BacktestTrade> trades;
    private final String symbol;
    private final String timeframe;
    private final int totalSignals;
    private final int longSignals;
    private final int shortSignals;
    private final double avgSignalsPerDay;
    private final double signalFrequencyPer100;

    public BacktestResult(String symbol, String timeframe, List<BacktestTrade> trades,
                          int longSignals, int shortSignals, long firstTimestamp, long lastTimestamp) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.trades = trades;
        this.totalTrades = trades.size();

        int wins = 0, losses = 0;
        BigDecimal grossP = BigDecimal.ZERO;
        BigDecimal grossL = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;

        for (BacktestTrade t : trades) {
            BigDecimal tradePnl = t.getPnl();
            pnl = pnl.add(tradePnl);
            if (tradePnl.compareTo(BigDecimal.ZERO) > 0) {
                wins++;
                grossP = grossP.add(tradePnl);
            } else {
                losses++;
                grossL = grossL.add(tradePnl.abs());
            }
        }

        this.winningTrades = wins;
        this.losingTrades = losses;
        this.totalPnl = pnl;
        this.grossProfit = grossP;
        this.grossLoss = grossL;

        this.winRate = totalTrades > 0 ? (double) wins / totalTrades : 0.0;
        this.profitFactor = grossL.compareTo(BigDecimal.ZERO) > 0
                ? grossP.divide(grossL, 4, RoundingMode.HALF_UP).doubleValue()
                : (grossP.compareTo(BigDecimal.ZERO) > 0 ? 999.99 : 0.0);

        // Max Drawdown calculation
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        BigDecimal runningBalance = BigDecimal.ZERO;
        for (BacktestTrade t : trades) {
            runningBalance = runningBalance.add(t.getPnl());
            if (runningBalance.compareTo(peak) > 0) {
                peak = runningBalance;
            }
            BigDecimal dd = peak.subtract(runningBalance);
            if (dd.compareTo(maxDd) > 0) {
                maxDd = dd;
            }
        }
        this.maxDrawdownPct = peak.compareTo(BigDecimal.ZERO) > 0
                ? maxDd.divide(peak, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue()
                : 0.0;

        // Sharpe Ratio (simplified: assume risk-free rate = 0)
        if (totalTrades > 1) {
            double avg = trades.stream().mapToDouble(t -> t.getPnl().doubleValue()).average().orElse(0.0);
            double variance = trades.stream()
                    .mapToDouble(t -> Math.pow(t.getPnl().doubleValue() - avg, 2))
                    .average().orElse(0.0);
            double stdDev = Math.sqrt(variance);
            this.sharpeRatio = stdDev > 0 ? avg / stdDev : 0.0;
        } else {
            this.sharpeRatio = 0.0;
        }

        // Signal frequency metrics
        this.longSignals = longSignals;
        this.shortSignals = shortSignals;
        this.totalSignals = longSignals + shortSignals;
        long durationMs = lastTimestamp - firstTimestamp;
        double durationDays = durationMs > 0 ? durationMs / (24.0 * 60.0 * 60.0 * 1000.0) : 0.0;
        this.avgSignalsPerDay = durationDays > 0 ? totalSignals / durationDays : 0.0;
        int totalBars = trades.isEmpty() ? 0 : trades.size(); // approximate, real value needs bar count
        this.signalFrequencyPer100 = totalBars > 0 ? (totalSignals * 100.0) / totalBars : 0.0;
    }

    public int getTotalTrades() { return totalTrades; }
    public int getWinningTrades() { return winningTrades; }
    public int getLosingTrades() { return losingTrades; }
    public BigDecimal getTotalPnl() { return totalPnl; }
    public BigDecimal getGrossProfit() { return grossProfit; }
    public BigDecimal getGrossLoss() { return grossLoss; }
    public double getWinRate() { return winRate; }
    public double getProfitFactor() { return profitFactor; }
    public double getMaxDrawdownPct() { return maxDrawdownPct; }
    public double getSharpeRatio() { return sharpeRatio; }
    public List<BacktestTrade> getTrades() { return trades; }
    public String getSymbol() { return symbol; }
    public String getTimeframe() { return timeframe; }
    public int getTotalSignals() { return totalSignals; }
    public int getLongSignals() { return longSignals; }
    public int getShortSignals() { return shortSignals; }
    public double getAvgSignalsPerDay() { return avgSignalsPerDay; }
    public double getSignalFrequencyPer100() { return signalFrequencyPer100; }

    @Override
    public String toString() {
        return String.format(
                "BacktestResult{symbol=%s, tf=%s, trades=%d, signals=%d (L=%d,S=%d), sig/day=%.2f, winRate=%.1f%%, pf=%.2f, maxDD=%.2f%%, sharpe=%.2f, pnl=%s}",
                symbol, timeframe, totalTrades, totalSignals, longSignals, shortSignals,
                avgSignalsPerDay, winRate * 100, profitFactor, maxDrawdownPct, sharpeRatio, totalPnl);
    }
}
