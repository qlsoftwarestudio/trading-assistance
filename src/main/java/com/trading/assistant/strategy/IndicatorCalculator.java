package com.trading.assistant.strategy;

import com.trading.assistant.binance.model.Kline;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class IndicatorCalculator {

    /**
     * Calculate RSI (Relative Strength Index)
     * Simplified calculation for 5-period RSI
     */
    public double calculateRSI(List<BigDecimal> closes) {
        if (closes == null || closes.size() < 6) {
            return 50.0; // Neutral if not enough data
        }

        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();

        // Calculate price changes
        for (int i = 1; i < closes.size(); i++) {
            double change = closes.get(i).subtract(closes.get(i - 1)).doubleValue();
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }

        // Use last 5 periods for RSI calculation
        int periods = 5;
        if (gains.size() < periods) {
            return 50.0;
        }

        double avgGain = gains.subList(gains.size() - periods, gains.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        double avgLoss = losses.subList(losses.size() - periods, losses.size())
                .stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        if (avgLoss == 0) {
            return 100.0; // No losses means RSI = 100
        }

        double rs = avgGain / avgLoss;
        double rsi = 100.0 - (100.0 / (1.0 + rs));

        return rsi;
    }

    /**
     * Calculate Session Low (lowest low in lookback period)
     */
    public double calculateSessionLow(List<BigDecimal> lows, int lookbackBars) {
        if (lows == null || lows.isEmpty()) {
            return 0.0;
        }

        int start = Math.max(0, lows.size() - lookbackBars);
        return lows.subList(start, lows.size())
                .stream()
                .mapToDouble(BigDecimal::doubleValue)
                .min()
                .orElse(0.0);
    }

    /**
     * Calculate momentum (percentage change)
     */
    public double calculateMomentum(List<BigDecimal> closes) {
        if (closes == null || closes.size() < 2) {
            return 0.0;
        }

        BigDecimal current = closes.get(closes.size() - 1);
        BigDecimal previous = closes.get(closes.size() - 2);

        if (previous.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0;
        }

        return current.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
    }

    /**
     * Check if price is in buy zone (near session low)
     */
    public boolean isInBuyZone(double currentPrice, double sessionLow, double thresholdPercent) {
        if (currentPrice <= 0 || sessionLow <= 0) {
            return false;
        }

        double distancePercent = ((currentPrice - sessionLow) / currentPrice) * 100.0;
        return distancePercent < thresholdPercent;
    }

    /**
     * Calculate Simple Moving Average
     */
    public double calculateSMA(List<BigDecimal> prices, int period) {
        if (prices == null || prices.size() < period) {
            return 0.0;
        }

        int start = prices.size() - period;
        return prices.subList(start, prices.size())
                .stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0);
    }

    // ============== KLINE-BASED METHODS ==============

    /**
     * Calculate RSI from Klines (using close prices)
     */
    public double calculateRSIFromKlines(List<Kline> klines) {
        if (klines == null || klines.size() < 6) {
            return 50.0;
        }
        List<BigDecimal> closes = klines.stream()
                .map(Kline::getClose)
                .collect(Collectors.toList());
        return calculateRSI(closes);
    }

    /**
     * Calculate Session Low from Klines
     */
    public double calculateSessionLowFromKlines(List<Kline> klines, int lookbackBars) {
        if (klines == null || klines.isEmpty()) {
            return 0.0;
        }
        List<BigDecimal> lows = klines.stream()
                .map(Kline::getLow)
                .collect(Collectors.toList());
        return calculateSessionLow(lows, lookbackBars);
    }

    /**
     * Calculate Momentum from Klines
     */
    public double calculateMomentumFromKlines(List<Kline> klines) {
        if (klines == null || klines.size() < 2) {
            return 0.0;
        }
        List<BigDecimal> closes = klines.stream()
                .map(Kline::getClose)
                .collect(Collectors.toList());
        return calculateMomentum(closes);
    }

    /**
     * Get current price from latest kline
     */
    public BigDecimal getCurrentPriceFromKlines(List<Kline> klines) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return klines.get(klines.size() - 1).getClose();
    }
}
