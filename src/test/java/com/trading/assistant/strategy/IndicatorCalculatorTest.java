package com.trading.assistant.strategy;

import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.strategy.model.MarketContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorCalculatorTest {

    private final IndicatorCalculator calculator = new IndicatorCalculator();

    @Test
    void testEMAUpwardTrend() {
        List<BigDecimal> prices = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            prices.add(BigDecimal.valueOf(i));
        }
        List<BigDecimal> ema = calculator.calculateEMA(prices, 20);
        assertFalse(ema.isEmpty());
        double lastEma = ema.get(ema.size() - 1).doubleValue();
        assertTrue(lastEma > 80, "EMA should converge close to current price in upward trend");
    }

    @Test
    void testDetectTrendUp() {
        List<Kline> klines = generateTrendKlines(250, 0.002); // upward trend
        MarketContext.TrendDirection trend = calculator.detectTrend(klines);
        assertEquals(MarketContext.TrendDirection.UP, trend);
    }

    @Test
    void testDetectTrendDown() {
        List<Kline> klines = generateTrendKlines(250, -0.002); // downward trend
        MarketContext.TrendDirection trend = calculator.detectTrend(klines);
        assertEquals(MarketContext.TrendDirection.DOWN, trend);
    }

    @Test
    void testSupportAndResistance() {
        List<Kline> klines = new ArrayList<>();
        BigDecimal base = new BigDecimal("100");
        for (int i = 0; i < 50; i++) {
            BigDecimal close = base.add(BigDecimal.valueOf(Math.sin(i * 0.5) * 5));
            BigDecimal high = close.add(BigDecimal.valueOf(1));
            BigDecimal low = close.subtract(BigDecimal.valueOf(1));
            klines.add(new Kline(i, close, high, low, close, BigDecimal.valueOf(1000)));
        }
        BigDecimal currentPrice = new BigDecimal("102");
        BigDecimal support = calculator.findNearestSupport(klines, currentPrice, 50);
        BigDecimal resistance = calculator.findNearestResistance(klines, currentPrice, 50);
        assertNotNull(support);
        assertNotNull(resistance);
        assertTrue(support.compareTo(currentPrice) < 0, "Support should be below current price");
        assertTrue(resistance.compareTo(currentPrice) > 0, "Resistance should be above current price");
    }

    @Test
    void testCorrelation() {
        List<BigDecimal> a = new ArrayList<>();
        List<BigDecimal> b = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            a.add(BigDecimal.valueOf(i));
            b.add(BigDecimal.valueOf(i * 2));
        }
        double corr = calculator.calculateCorrelation(a, b);
        assertTrue(corr > 0.99, "Perfect linear correlation expected");
    }

    @Test
    void testRelativeVolume() {
        List<Kline> klines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // Implementation uses size-2 (penultimate candle), so spike must be at index 28
            BigDecimal vol = i == 28 ? BigDecimal.valueOf(20000) : BigDecimal.valueOf(5000);
            klines.add(new Kline(i, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, vol));
        }
        double relVol = calculator.calculateRelativeVolume(klines, 10);
        assertTrue(relVol > 1.5, "Penultimate volume (4x average) should produce relVol>1.5, got: " + relVol);
    }

    private List<Kline> generateTrendKlines(int count, double dailyChange) {
        List<Kline> klines = new ArrayList<>();
        BigDecimal price = new BigDecimal("100");
        for (int i = 0; i < count; i++) {
            BigDecimal change = price.multiply(BigDecimal.valueOf(dailyChange));
            price = price.add(change);
            BigDecimal high = price.multiply(BigDecimal.valueOf(1.005));
            BigDecimal low = price.multiply(BigDecimal.valueOf(0.995));
            klines.add(new Kline(i, price, high, low, price, BigDecimal.valueOf(10000)));
        }
        return klines;
    }
}
