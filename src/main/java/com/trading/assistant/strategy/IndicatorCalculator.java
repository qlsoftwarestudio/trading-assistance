package com.trading.assistant.strategy;

import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.strategy.model.LinearRegressionChannel;
import com.trading.assistant.strategy.model.PriceProjection;
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
        if (avgGain == 0) {
            return 5.0; // All periods losing — extreme oversold, floor at 5 to avoid RSI=0 anomaly
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
        // Use previous completed candle (size-2) instead of in-progress last candle (size-1)
        if (klines == null || klines.size() < lookbackBars + 2) {
            return 1.0;
        }
        BigDecimal currentVolume = klines.get(klines.size() - 2).getVolume();
        int start = Math.max(0, klines.size() - lookbackBars - 2);
        double avgVolume = klines.subList(start, klines.size() - 2).stream()
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

    // ============== VWAP ==============

    /**
     * Calculate Volume Weighted Average Price (VWAP) from klines.
     * VWAP = sum(typical_price * volume) / sum(volume)
     */
    public BigDecimal calculateVWAP(List<Kline> klines) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal cumulativeTPV = BigDecimal.ZERO;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;
        for (Kline k : klines) {
            BigDecimal typicalPrice = k.getHigh().add(k.getLow()).add(k.getClose())
                    .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP);
            BigDecimal volume = k.getVolume();
            if (volume.compareTo(BigDecimal.ZERO) > 0) {
                cumulativeTPV = cumulativeTPV.add(typicalPrice.multiply(volume));
                cumulativeVolume = cumulativeVolume.add(volume);
            }
        }
        if (cumulativeVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return cumulativeTPV.divide(cumulativeVolume, 8, RoundingMode.HALF_UP);
    }

    // ============== BREAKOUT DETECTION ==============

    // ============== LINEAR REGRESSION CHANNEL ==============

    /**
     * Calculate a linear regression channel over the last N closes.
     * Uses ordinary least squares: y = slope*x + intercept
     * Channel bands = regression line ± 1 standard deviation of residuals.
     * pricePosition: 0.0 = price at lower band, 1.0 = at upper band.
     */
    public LinearRegressionChannel calculateLinearRegressionChannel(
            List<Kline> klines, int lookbackBars, int candlesAhead) {
        int n = Math.min(lookbackBars, klines.size());
        if (n < 5) {
            return null;
        }

        int startIdx = klines.size() - n;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = klines.get(startIdx + i).getClose().doubleValue();
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0) {
            return null;
        }
        double slope     = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;

        double sumResiduals2 = 0;
        for (int i = 0; i < n; i++) {
            double y    = klines.get(startIdx + i).getClose().doubleValue();
            double yHat = intercept + slope * i;
            double res  = y - yHat;
            sumResiduals2 += res * res;
        }
        double stddev = Math.sqrt(sumResiduals2 / n);

        double midline          = intercept + slope * (n - 1);
        double upperBand        = midline + stddev;
        double lowerBand        = midline - stddev;
        double projectedMidline = intercept + slope * (n - 1 + candlesAhead);
        double channelWidthPct  = midline > 0 ? (upperBand - lowerBand) / midline * 100.0 : 0.0;

        double currentPrice = getCurrentPriceFromKlines(klines).doubleValue();
        double pricePosition = (upperBand - lowerBand) > 0
                ? (currentPrice - lowerBand) / (upperBand - lowerBand)
                : 0.5;
        pricePosition = Math.max(0.0, Math.min(1.0, pricePosition));

        double slopePct = midline > 0 ? slope / midline * 100.0 : 0.0;
        LinearRegressionChannel.ChannelDirection direction;
        if      (slopePct >  0.015) direction = LinearRegressionChannel.ChannelDirection.UP;
        else if (slopePct < -0.015) direction = LinearRegressionChannel.ChannelDirection.DOWN;
        else                        direction = LinearRegressionChannel.ChannelDirection.SIDEWAYS;

        return new LinearRegressionChannel(slope, slopePct, midline, upperBand, lowerBand,
                projectedMidline, channelWidthPct, pricePosition, direction, n);
    }

    // ============== PRICE PROJECTION ==============

    /**
     * ATR-based price range projection for the next N candles.
     * Uses sqrt(time) scaling: expected range grows with the square root of the number of candles ahead.
     * Also checks whether TP targets are reachable within the projected range.
     */
    public PriceProjection calculatePriceProjection(List<Kline> klines, int atrPeriod, int candlesAhead, double takeProfitPct) {
        if (klines == null || klines.size() < atrPeriod + 1) {
            return null;
        }
        double atr = calculateATR(klines, atrPeriod);
        double currentPrice = getCurrentPriceFromKlines(klines).doubleValue();
        if (atr <= 0 || currentPrice <= 0) {
            return null;
        }

        double projectedRange = atr * Math.sqrt(candlesAhead);
        double projectedHigh = currentPrice + projectedRange;
        double projectedLow  = currentPrice - projectedRange;

        double longTP  = currentPrice * (1.0 + takeProfitPct / 100.0);
        double shortTP = currentPrice * (1.0 - takeProfitPct / 100.0);
        boolean tpReachableLong  = longTP  <= projectedHigh;
        boolean tpReachableShort = shortTP >= projectedLow;

        return new PriceProjection(projectedHigh, projectedLow, atr, candlesAhead,
                tpReachableLong, tpReachableShort);
    }

    /**
     * Check if current price broke above the session high (momentum breakout).
     */
    public boolean isBreakoutAbove(double currentPrice, double sessionHigh) {
        return currentPrice > sessionHigh;
    }

    /**
     * Check if current price broke below the session low (momentum breakdown).
     */
    public boolean isBreakoutBelow(double currentPrice, double sessionLow) {
        return currentPrice < sessionLow;
    }

    /**
     * Estimate volume delta (buy pressure vs sell pressure) for a single kline.
     * Uses the position of the close within the high-low range as a proxy for
     * buy/sell aggression. A close near the high suggests more buying pressure,
     * a close near the low suggests more selling pressure.
     *
     * @return Estimated delta as a ratio of volume: positive = more buys, negative = more sells.
     *         Range: [-volume, +volume]. Returns 0 if high == low (doji).
     */
    public double estimateVolumeDelta(Kline kline) {
        if (kline == null) return 0;
        BigDecimal high = kline.getHigh();
        BigDecimal low = kline.getLow();
        BigDecimal close = kline.getClose();
        BigDecimal volume = kline.getVolume();
        if (high == null || low == null || close == null || volume == null) return 0;
        if (high.compareTo(low) == 0) return 0; // Doji, no directional pressure

        // Normalize close position within the range: 0 = at low, 1 = at high
        double position = close.subtract(low).doubleValue()
                / high.subtract(low).doubleValue();

        // Map to [-1, 1]: -1 = all sells, +1 = all buys
        double deltaRatio = (position - 0.5) * 2.0;

        return volume.doubleValue() * deltaRatio;
    }

    /**
     * Calculate Stochastic Oscillator (%K and %D).
     *
     * Raw %K = (close - lowest_low_period) / (highest_high_period - lowest_low_period) × 100
     * Smoothed %K = SMA(raw %K, smoothK)
     * %D = SMA(smoothed %K, smoothD)
     *
     * @return double[] { smoothed %K, %D } — both in range [0, 100]
     */
    public double[] calculateStochastic(List<Kline> klines, int period, int smoothK, int smoothD) {
        int minRequired = period + smoothK + smoothD - 2;
        if (klines == null || klines.size() < minRequired) {
            return new double[]{50.0, 50.0};
        }
        int size = klines.size();

        // Compute raw %K for each candle from index (period-1) onwards
        List<Double> rawK = new ArrayList<>();
        for (int i = period - 1; i < size; i++) {
            double highest = Double.MIN_VALUE;
            double lowest  = Double.MAX_VALUE;
            for (int j = i - period + 1; j <= i; j++) {
                double h = klines.get(j).getHigh().doubleValue();
                double l = klines.get(j).getLow().doubleValue();
                if (h > highest) highest = h;
                if (l < lowest)  lowest  = l;
            }
            double c = klines.get(i).getClose().doubleValue();
            double k = (highest == lowest) ? 50.0 : ((c - lowest) / (highest - lowest)) * 100.0;
            rawK.add(k);
        }

        // Smooth %K with SMA(smoothK)
        List<Double> smoothedK = new ArrayList<>();
        for (int i = smoothK - 1; i < rawK.size(); i++) {
            double sum = 0;
            for (int j = i - smoothK + 1; j <= i; j++) sum += rawK.get(j);
            smoothedK.add(sum / smoothK);
        }

        if (smoothedK.isEmpty()) return new double[]{50.0, 50.0};

        // %D = SMA(smoothedK, smoothD)
        double d;
        if (smoothedK.size() >= smoothD) {
            double sum = 0;
            for (int i = smoothedK.size() - smoothD; i < smoothedK.size(); i++) sum += smoothedK.get(i);
            d = sum / smoothD;
        } else {
            d = smoothedK.get(smoothedK.size() - 1);
        }

        return new double[]{smoothedK.get(smoothedK.size() - 1), d};
    }

    /**
     * Calculate Bollinger Bands.
     *
     * mid   = SMA(close, period)
     * upper = mid + stdDevMultiplier × σ(close, period)
     * lower = mid − stdDevMultiplier × σ(close, period)
     *
     * @return double[] { upper, mid, lower }
     */
    public double[] calculateBollingerBands(List<Kline> klines, int period, double stdDevMultiplier) {
        if (klines == null || klines.size() < period) {
            return new double[]{0.0, 0.0, 0.0};
        }
        int size = klines.size();
        double sum = 0;
        for (int i = size - period; i < size; i++) sum += klines.get(i).getClose().doubleValue();
        double mid = sum / period;

        double variance = 0;
        for (int i = size - period; i < size; i++) {
            double diff = klines.get(i).getClose().doubleValue() - mid;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);

        return new double[]{mid + stdDevMultiplier * stdDev, mid, mid - stdDevMultiplier * stdDev};
    }

    /**
     * Returns how far the price is from a BB band, as a percentage of the band value.
     * Positive = price is above the band; negative = price is below.
     */
    public double getBBDistancePct(double price, double band) {
        if (band <= 0) return 999.0;
        return (price - band) / band * 100.0;
    }

    /**
     * Detects if the market is in balance (consolidation/chop).
     * Compares short-period ATR vs long-period ATR.
     * If short ATR / long ATR < compressionRatio, the market is compressing = balance.
     *
     * @param klines              full kline list (descending time or ascending — must be sorted by time ascending)
     * @param shortPeriod         e.g. 20 candles (recent volatility window)
     * @param longPeriod          e.g. 50 candles (baseline volatility window)
     * @param compressionRatio    e.g. 0.30 → short ATR must be < 30% of long ATR
     * @return true if market is in balance (chop / consolidation)
     */
    public boolean isMarketInBalance(List<Kline> klines, int shortPeriod, int longPeriod, double compressionRatio) {
        if (klines == null || klines.size() < longPeriod + 1) {
            return false; // Not enough data to judge
        }
        double shortATR = calculateATR(klines.subList(klines.size() - shortPeriod - 1, klines.size()), shortPeriod);
        double longATR  = calculateATR(klines, longPeriod);
        if (longATR <= 0) return false;
        return (shortATR / longATR) < compressionRatio;
    }

    /**
     * Detects absorption: high volume spike with small candle range.
     * Indicates institutions accumulating/distributing at a key level before a move.
     *
     * Logic: volume > avgVolume × volumeMultiplier  AND  candleRange < ATR × rangeMultiplier
     *
     * @param currentKline        the most recent candle
     * @param klines            recent history for volume average and ATR
     * @param volumeMultiplier  e.g. 2.0 → volume must be 2× average
     * @param rangeMultiplier   e.g. 0.3 → candle range must be < 30% of recent ATR
     * @return true if absorption pattern detected
     */
    public boolean detectAbsorption(Kline currentKline, List<Kline> klines, double volumeMultiplier, double rangeMultiplier) {
        if (currentKline == null || klines == null || klines.size() < 20) {
            return false;
        }
        // Average volume of last 20 candles (excluding current)
        double avgVolume = 0;
        int count = Math.min(20, klines.size() - 1);
        for (int i = klines.size() - count - 1; i < klines.size() - 1; i++) {
            avgVolume += klines.get(i).getVolume().doubleValue();
        }
        avgVolume /= count;

        double currentVolume = currentKline.getVolume().doubleValue();
        if (avgVolume <= 0 || currentVolume <= 0) return false;

        // Candle range
        double candleRange = currentKline.getHigh().subtract(currentKline.getLow()).doubleValue();

        // ATR of last 14 candles up to (but not including) current
        int atrPeriod = 14;
        if (klines.size() < atrPeriod + 2) return false;
        List<Kline> atrKlines = klines.subList(klines.size() - atrPeriod - 2, klines.size() - 1);
        double atr = calculateATR(atrKlines, atrPeriod);
        if (atr <= 0) return false;

        boolean volumeSpike = currentVolume > avgVolume * volumeMultiplier;
        boolean smallRange  = candleRange < atr * rangeMultiplier;

        return volumeSpike && smallRange;
    }
}
