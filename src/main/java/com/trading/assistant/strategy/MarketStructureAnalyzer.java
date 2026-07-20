package com.trading.assistant.strategy;

import com.trading.assistant.binance.model.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Market Structure & Institutional Zones analyzer (SMC / price-action).
 *
 * Provides two building blocks used to filter the M5 mean-reversion strategy:
 *
 *  1. HTF (High Time Frame) macro structure — computed on H1 candles by comparing
 *     structural swing highs/lows. Higher-High + Higher-Low = BULLISH; Lower-High +
 *     Lower-Low = BEARISH; anything else = NEUTRAL/range.
 *
 *  2. Order Blocks — the last opposite-colour candle before a strong displacement
 *     move that breaks the previous swing (Market Structure Shift). A bullish OB is
 *     the last bearish candle before an up-move that breaks the last swing high;
 *     a bearish OB is the last bullish candle before a down-move that breaks the
 *     last swing low. The M5 trigger only fires when price is inside a valid OB.
 *
 * All detection is purely price/candle based — no external data.
 */
@Component
public class MarketStructureAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(MarketStructureAnalyzer.class);

    public enum Structure { BULLISH, BEARISH, NEUTRAL }

    /**
     * A price zone left by an institutional order block.
     */
    public static class OrderBlock {
        public final double low;
        public final double high;
        public final boolean bullish; // true = demand zone (for LONGs), false = supply zone (for SHORTs)
        public final long timestamp;

        public OrderBlock(double low, double high, boolean bullish, long timestamp) {
            this.low = low;
            this.high = high;
            this.bullish = bullish;
            this.timestamp = timestamp;
        }

        public boolean contains(double price) {
            return price >= low && price <= high;
        }

        @Override
        public String toString() {
            return String.format("%s OB [%.5f - %.5f]", bullish ? "BULL" : "BEAR", low, high);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. HTF macro structure (H1)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Determine the macro trend from a set of HTF candles (typically H1) by comparing
     * the last two structural swing highs and swing lows.
     *
     * @param klines       HTF candles (chronological, oldest first)
     * @param pivotStrength number of candles on each side used to confirm a swing (fractal strength)
     */
    public Structure analyzeMacroStructure(List<Kline> klines, int pivotStrength) {
        if (klines == null || klines.size() < pivotStrength * 2 + 3) {
            return Structure.NEUTRAL;
        }

        List<Integer> swingHighs = findSwingHighs(klines, pivotStrength);
        List<Integer> swingLows = findSwingLows(klines, pivotStrength);

        if (swingHighs.size() < 2 || swingLows.size() < 2) {
            return Structure.NEUTRAL;
        }

        double lastHigh = klines.get(swingHighs.get(swingHighs.size() - 1)).getHigh().doubleValue();
        double prevHigh = klines.get(swingHighs.get(swingHighs.size() - 2)).getHigh().doubleValue();
        double lastLow = klines.get(swingLows.get(swingLows.size() - 1)).getLow().doubleValue();
        double prevLow = klines.get(swingLows.get(swingLows.size() - 2)).getLow().doubleValue();

        boolean higherHigh = lastHigh > prevHigh;
        boolean higherLow = lastLow > prevLow;
        boolean lowerHigh = lastHigh < prevHigh;
        boolean lowerLow = lastLow < prevLow;

        if (higherHigh && higherLow) return Structure.BULLISH;
        if (lowerHigh && lowerLow) return Structure.BEARISH;
        return Structure.NEUTRAL;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. Order Blocks
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Detect the most recent valid, unmitigated order blocks in the given candles.
     *
     * An OB is anchored by a displacement move (a candle whose body is at least
     * {@code displacementBodyAtr} × ATR and that closes beyond the prior swing point,
     * i.e. a Market Structure Shift). The block itself is the last opposite-colour
     * candle before the displacement.
     *
     * @param klines              candles (chronological, oldest first)
     * @param pivotStrength       fractal strength used to locate swing points broken by the move
     * @param displacementBodyAtr min displacement body size expressed in multiples of ATR
     * @param atr                 current ATR of the same timeframe (must be > 0)
     * @param maxBlocks           keep only the N most recent blocks
     */
    public List<OrderBlock> findOrderBlocks(List<Kline> klines, int pivotStrength,
                                            double displacementBodyAtr, double atr, int maxBlocks) {
        List<OrderBlock> blocks = new ArrayList<>();
        if (klines == null || klines.size() < pivotStrength * 2 + 3 || atr <= 0) {
            return blocks;
        }

        double minBody = displacementBodyAtr * atr;
        int n = klines.size();

        // Scan candles looking for a displacement that breaks recent structure.
        for (int i = pivotStrength + 1; i < n - 1; i++) {
            Kline k = klines.get(i);
            double open = k.getOpen().doubleValue();
            double close = k.getClose().doubleValue();
            double body = close - open;

            // Bullish displacement: strong up candle that breaks the most recent swing high before it.
            if (body >= minBody) {
                double recentSwingHigh = recentSwingHighBefore(klines, i, pivotStrength);
                if (!Double.isNaN(recentSwingHigh) && close > recentSwingHigh) {
                    int obIdx = lastBearishBefore(klines, i);
                    if (obIdx >= 0) {
                        Kline ob = klines.get(obIdx);
                        blocks.add(new OrderBlock(ob.getLow().doubleValue(), ob.getHigh().doubleValue(),
                                true, ob.getTimestamp()));
                    }
                }
            }

            // Bearish displacement: strong down candle that breaks the most recent swing low before it.
            if (body <= -minBody) {
                double recentSwingLow = recentSwingLowBefore(klines, i, pivotStrength);
                if (!Double.isNaN(recentSwingLow) && close < recentSwingLow) {
                    int obIdx = lastBullishBefore(klines, i);
                    if (obIdx >= 0) {
                        Kline ob = klines.get(obIdx);
                        blocks.add(new OrderBlock(ob.getLow().doubleValue(), ob.getHigh().doubleValue(),
                                false, ob.getTimestamp()));
                    }
                }
            }
        }

        // Remove mitigated blocks: a block is invalidated once price fully trades back through it.
        double lastClose = klines.get(n - 1).getClose().doubleValue();
        List<OrderBlock> unmitigated = new ArrayList<>();
        for (OrderBlock b : blocks) {
            // A demand (bull) OB is spent once price closes clearly below it; supply (bear) once above.
            if (b.bullish && lastClose < b.low) continue;
            if (!b.bullish && lastClose > b.high) continue;
            unmitigated.add(b);
        }

        // Keep the most recent N.
        int from = Math.max(0, unmitigated.size() - maxBlocks);
        return new ArrayList<>(unmitigated.subList(from, unmitigated.size()));
    }

    /** True if price sits inside a bullish (demand) order block. */
    public boolean isPriceInBullishOB(double price, List<OrderBlock> obs) {
        if (obs == null) return false;
        return obs.stream().anyMatch(b -> b.bullish && b.contains(price));
    }

    /** True if price sits inside a bearish (supply) order block. */
    public boolean isPriceInBearishOB(double price, List<OrderBlock> obs) {
        if (obs == null) return false;
        return obs.stream().anyMatch(b -> !b.bullish && b.contains(price));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Swing / fractal helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /** Indices of confirmed swing highs (fractal pivots). */
    public List<Integer> findSwingHighs(List<Kline> klines, int strength) {
        List<Integer> out = new ArrayList<>();
        if (klines == null) return out;
        for (int i = strength; i < klines.size() - strength; i++) {
            double h = klines.get(i).getHigh().doubleValue();
            boolean pivot = true;
            for (int j = i - strength; j <= i + strength; j++) {
                if (j == i) continue;
                if (klines.get(j).getHigh().doubleValue() > h) { pivot = false; break; }
            }
            if (pivot) out.add(i);
        }
        return out;
    }

    /** Indices of confirmed swing lows (fractal pivots). */
    public List<Integer> findSwingLows(List<Kline> klines, int strength) {
        List<Integer> out = new ArrayList<>();
        if (klines == null) return out;
        for (int i = strength; i < klines.size() - strength; i++) {
            double l = klines.get(i).getLow().doubleValue();
            boolean pivot = true;
            for (int j = i - strength; j <= i + strength; j++) {
                if (j == i) continue;
                if (klines.get(j).getLow().doubleValue() < l) { pivot = false; break; }
            }
            if (pivot) out.add(i);
        }
        return out;
    }

    /**
     * Most recent confirmed swing low value (for structural SL on LONGs).
     * Returns NaN if none found.
     */
    public double lastSwingLow(List<Kline> klines, int strength) {
        List<Integer> lows = findSwingLows(klines, strength);
        if (lows.isEmpty()) return Double.NaN;
        return klines.get(lows.get(lows.size() - 1)).getLow().doubleValue();
    }

    /**
     * Most recent confirmed swing high value (for structural SL on SHORTs).
     * Returns NaN if none found.
     */
    public double lastSwingHigh(List<Kline> klines, int strength) {
        List<Integer> highs = findSwingHighs(klines, strength);
        if (highs.isEmpty()) return Double.NaN;
        return klines.get(highs.get(highs.size() - 1)).getHigh().doubleValue();
    }

    private double recentSwingHighBefore(List<Kline> klines, int beforeIdx, int strength) {
        double best = Double.NaN;
        for (int i = strength; i < beforeIdx - strength; i++) {
            double h = klines.get(i).getHigh().doubleValue();
            boolean pivot = true;
            for (int j = i - strength; j <= i + strength; j++) {
                if (j == i) continue;
                if (klines.get(j).getHigh().doubleValue() > h) { pivot = false; break; }
            }
            if (pivot) best = h; // keep last (closest to beforeIdx)
        }
        return best;
    }

    private double recentSwingLowBefore(List<Kline> klines, int beforeIdx, int strength) {
        double best = Double.NaN;
        for (int i = strength; i < beforeIdx - strength; i++) {
            double l = klines.get(i).getLow().doubleValue();
            boolean pivot = true;
            for (int j = i - strength; j <= i + strength; j++) {
                if (j == i) continue;
                if (klines.get(j).getLow().doubleValue() < l) { pivot = false; break; }
            }
            if (pivot) best = l;
        }
        return best;
    }

    private int lastBearishBefore(List<Kline> klines, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            Kline k = klines.get(i);
            if (k.getClose().doubleValue() < k.getOpen().doubleValue()) return i;
        }
        return -1;
    }

    private int lastBullishBefore(List<Kline> klines, int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            Kline k = klines.get(i);
            if (k.getClose().doubleValue() > k.getOpen().doubleValue()) return i;
        }
        return -1;
    }
}
