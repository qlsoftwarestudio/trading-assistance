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
     * Check if price is in buy zone (bottom % of the session range).
     * zonePercent = 25 means bottom 25% of the range (low to high).
     * Mutually exclusive with isInSellZone.
     */
    public boolean isInBuyZone(double currentPrice, double sessionLow, double sessionHigh, double zonePercent) {
        if (currentPrice <= 0 || sessionLow <= 0 || sessionHigh <= 0) {
            return false;
        }
        double range = sessionHigh - sessionLow;
        if (range <= 0) {
            return false;
        }
        double positionInRange = (currentPrice - sessionLow) / range;
        return positionInRange <= zonePercent / 100.0;
    }

    /**
     * Calculate Session High (highest high in lookback period)
     */
    public double calculateSessionHigh(List<BigDecimal> highs, int lookbackBars) {
        if (highs == null || highs.isEmpty()) {
            return 0.0;
        }

        int start = Math.max(0, highs.size() - lookbackBars);
        return highs.subList(start, highs.size())
                .stream()
                .mapToDouble(BigDecimal::doubleValue)
                .max()
                .orElse(0.0);
    }

    /**
     * Check if price is in sell zone (top % of the session range).
     * zonePercent = 25 means top 25% of the range (high to low).
     * Mutually exclusive with isInBuyZone.
     */
    public boolean isInSellZone(double currentPrice, double sessionLow, double sessionHigh, double zonePercent) {
        if (currentPrice <= 0 || sessionLow <= 0 || sessionHigh <= 0) {
            return false;
        }
        double range = sessionHigh - sessionLow;
        if (range <= 0) {
            return false;
        }
        double positionInRange = (sessionHigh - currentPrice) / range;
        return positionInRange <= zonePercent / 100.0;
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
     * Calculate Session High from Klines
     */
    public double calculateSessionHighFromKlines(List<Kline> klines, int lookbackBars) {
        if (klines == null || klines.isEmpty()) {
            return 0.0;
        }
        List<BigDecimal> highs = klines.stream()
                .map(Kline::getHigh)
                .collect(Collectors.toList());
        return calculateSessionHigh(highs, lookbackBars);
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

    // ============== EMA & TREND DETECTION ==============

    /**
     * Calculate Exponential Moving Average
     */
    public List<BigDecimal> calculateEMA(List<BigDecimal> prices, int period) {
        List<BigDecimal> ema = new ArrayList<>();
        if (prices == null || prices.size() < period) {
            return ema;
        }

        double multiplier = 2.0 / (period + 1.0);
        // First EMA = SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += prices.get(i).doubleValue();
        }
        ema.add(BigDecimal.valueOf(sum / period));

        for (int i = period; i < prices.size(); i++) {
            double prevEma = ema.get(ema.size() - 1).doubleValue();
            double currentPrice = prices.get(i).doubleValue();
            double currentEma = (currentPrice - prevEma) * multiplier + prevEma;
            ema.add(BigDecimal.valueOf(currentEma));
        }
        return ema;
    }

    /**
     * Get last EMA value from klines
     */
    public double calculateEMAFromKlines(List<Kline> klines, int period) {
        List<BigDecimal> closes = klines.stream()
                .map(Kline::getClose)
                .collect(Collectors.toList());
        List<BigDecimal> ema = calculateEMA(closes, period);
        return ema.isEmpty() ? 0.0 : ema.get(ema.size() - 1).doubleValue();
    }

    /**
     * Detect trend direction based on EMA alignment
     */
    public com.trading.assistant.strategy.model.MarketContext.TrendDirection detectTrend(List<Kline> klines) {
        List<BigDecimal> closes = klines.stream()
                .map(Kline::getClose)
                .collect(Collectors.toList());

        double ema20 = calculateEMA(closes, 20).stream()
                .reduce((a, b) -> b).orElse(BigDecimal.ZERO).doubleValue();
        double ema50 = calculateEMA(closes, 50).stream()
                .reduce((a, b) -> b).orElse(BigDecimal.ZERO).doubleValue();
        double ema200 = calculateEMA(closes, 200).stream()
                .reduce((a, b) -> b).orElse(BigDecimal.ZERO).doubleValue();

        if (ema20 > ema50 && ema50 > ema200) {
            return com.trading.assistant.strategy.model.MarketContext.TrendDirection.UP;
        }
        if (ema20 < ema50 && ema50 < ema200) {
            return com.trading.assistant.strategy.model.MarketContext.TrendDirection.DOWN;
        }
        return com.trading.assistant.strategy.model.MarketContext.TrendDirection.SIDEWAYS;
    }

    // ============== VOLUME ANALYSIS ==============

    /**
     * Calculate relative volume: current volume vs average volume over lookback
     */
    public double calculateRelativeVolume(List<Kline> klines, int lookbackBars) {
        if (klines == null || klines.size() < lookbackBars + 1) {
            return 1.0;
        }
        BigDecimal currentVolume = klines.get(klines.size() - 1).getVolume();
        int start = Math.max(0, klines.size() - lookbackBars - 1);
        double avgVolume = klines.subList(start, klines.size() - 1).stream()
                .mapToDouble(k -> k.getVolume().doubleValue())
                .average()
                .orElse(1.0);
        if (avgVolume == 0) return 1.0;
        return currentVolume.doubleValue() / avgVolume;
    }

    /**
     * Calculate OBV (On-Balance Volume) slope over lookback periods
     */
    public double calculateOBVSlope(List<Kline> klines, int lookbackBars) {
        if (klines == null || klines.size() < lookbackBars + 1) {
            return 0.0;
        }
        long obv = 0;
        for (int i = 1; i < klines.size(); i++) {
            BigDecimal prevClose = klines.get(i - 1).getClose();
            BigDecimal currClose = klines.get(i).getClose();
            BigDecimal volume = klines.get(i).getVolume();
            if (currClose.compareTo(prevClose) > 0) {
                obv += volume.longValue();
            } else if (currClose.compareTo(prevClose) < 0) {
                obv -= volume.longValue();
            }
        }
        int start = Math.max(0, klines.size() - lookbackBars - 1);
        long obvStart = 0;
        for (int i = 1; i <= start && i < klines.size(); i++) {
            BigDecimal prevClose = klines.get(i - 1).getClose();
            BigDecimal currClose = klines.get(i).getClose();
            BigDecimal volume = klines.get(i).getVolume();
            if (currClose.compareTo(prevClose) > 0) {
                obvStart += volume.longValue();
            } else if (currClose.compareTo(prevClose) < 0) {
                obvStart -= volume.longValue();
            }
        }
        long obvChange = obv - obvStart;
        double avgVolume = klines.stream()
                .mapToDouble(k -> k.getVolume().doubleValue())
                .average()
                .orElse(1.0);
        if (avgVolume == 0) return 0.0;
        return obvChange / avgVolume;
    }

    // ============== SUPPORT & RESISTANCE ==============

    /**
     * Find nearest support level (significant low in recent history)
     */
    public BigDecimal findNearestSupport(List<Kline> klines, BigDecimal currentPrice, int lookbackBars) {
        if (klines == null || klines.size() < 3 || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        int start = Math.max(0, klines.size() - lookbackBars);
        BigDecimal nearest = BigDecimal.ZERO;
        double minDistance = Double.MAX_VALUE;
        for (int i = start; i < klines.size(); i++) {
            BigDecimal low = klines.get(i).getLow();
            if (low.compareTo(currentPrice) < 0) {
                double distance = currentPrice.subtract(low).doubleValue();
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = low;
                }
            }
        }
        return nearest;
    }

    /**
     * Find nearest resistance level (significant high in recent history)
     */
    public BigDecimal findNearestResistance(List<Kline> klines, BigDecimal currentPrice, int lookbackBars) {
        if (klines == null || klines.size() < 3 || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        int start = Math.max(0, klines.size() - lookbackBars);
        BigDecimal nearest = BigDecimal.ZERO;
        double minDistance = Double.MAX_VALUE;
        for (int i = start; i < klines.size(); i++) {
            BigDecimal high = klines.get(i).getHigh();
            if (high.compareTo(currentPrice) > 0) {
                double distance = high.subtract(currentPrice).doubleValue();
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = high;
                }
            }
        }
        return nearest;
    }

    /**
     * Calculate distance to support/resistance as percentage
     */
    public double distanceToLevelPct(BigDecimal currentPrice, BigDecimal level) {
        if (currentPrice.compareTo(BigDecimal.ZERO) == 0 || level.compareTo(BigDecimal.ZERO) == 0) {
            return 100.0;
        }
        return Math.abs(level.subtract(currentPrice).doubleValue()) / currentPrice.doubleValue() * 100.0;
    }

    // ============== ATR (Average True Range) ==============

    /**
     * Calculate ATR for volatility-based stop loss.
     * ATR measures the average range of each candle (high-low including gaps).
     * A common SL placement is 2x ATR below entry for LONG.
     */
    public double calculateATR(List<Kline> klines, int period) {
        if (klines == null || klines.size() < period + 1) {
            return 0.0;
        }
        double[] trueRanges = new double[klines.size()];
        for (int i = 1; i < klines.size(); i++) {
            Kline current = klines.get(i);
            Kline previous = klines.get(i - 1);
            double highLow = current.getHigh().subtract(current.getLow()).doubleValue();
            double highPrevClose = Math.abs(current.getHigh().subtract(previous.getClose()).doubleValue());
            double lowPrevClose = Math.abs(current.getLow().subtract(previous.getClose()).doubleValue());
            trueRanges[i] = Math.max(highLow, Math.max(highPrevClose, lowPrevClose));
        }
        // Wilder's smoothing for ATR
        double atr = 0;
        for (int i = 1; i <= period; i++) {
            atr += trueRanges[i];
        }
        atr /= period;
        for (int i = period + 1; i < trueRanges.length; i++) {
            atr = (atr * (period - 1) + trueRanges[i]) / period;
        }
        return atr;
    }

    public BigDecimal atrBasedStopLoss(BigDecimal entryPrice, double atr, int multiplier, boolean isLong) {
        BigDecimal atrValue = BigDecimal.valueOf(atr * multiplier);
        if (isLong) {
            return entryPrice.subtract(atrValue).max(BigDecimal.ZERO);
        } else {
            return entryPrice.add(atrValue);
        }
    }

    // ============== CORRELATION ==============

    /**
     * Calculate Pearson correlation between two price series
     */
    public double calculateCorrelation(List<BigDecimal> seriesA, List<BigDecimal> seriesB) {
        if (seriesA == null || seriesB == null || seriesA.size() != seriesB.size() || seriesA.size() < 2) {
            return 0.0;
        }
        int n = seriesA.size();
        double sumA = 0, sumB = 0, sumAB = 0, sumA2 = 0, sumB2 = 0;
        for (int i = 0; i < n; i++) {
            double a = seriesA.get(i).doubleValue();
            double b = seriesB.get(i).doubleValue();
            sumA += a;
            sumB += b;
            sumAB += a * b;
            sumA2 += a * a;
            sumB2 += b * b;
        }
        double numerator = n * sumAB - sumA * sumB;
        double denominator = Math.sqrt((n * sumA2 - sumA * sumA) * (n * sumB2 - sumB * sumB));
        if (denominator == 0) return 0.0;
        return numerator / denominator;
    }
}
