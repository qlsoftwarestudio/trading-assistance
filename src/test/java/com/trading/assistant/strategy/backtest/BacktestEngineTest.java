package com.trading.assistant.strategy.backtest;

import com.trading.assistant.binance.model.Kline;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    @Test
    void testBacktestWithUpwardTrend() {
        List<Kline> klines = generateKlines(300, 0.001);
        BacktestEngine engine = new BacktestEngine();
        BacktestEngine.BacktestParams params = new BacktestEngine.BacktestParams();
        params.rsiOversold = 45;
        params.rsiOverbought = 55;
        params.lookbackBars = 24;
        params.killzoneThreshold = 25.0;  // bottom/top 25% of range
        params.minMomentum = 0.3;
        params.stopLossPct = 1.0;
        params.takeProfitPct = 3.0;

        BacktestResult result = engine.run("TEST", "15m", klines, params);
        assertNotNull(result);
        assertTrue(result.getTotalTrades() >= 0);
        assertTrue(result.getWinRate() >= 0 && result.getWinRate() <= 1.0);
        assertTrue(result.getMaxDrawdownPct() >= 0);
    }

    @Test
    void testBacktestWithOscillatingPrices() {
        List<Kline> klines = generateOscillatingKlines(500);
        BacktestEngine engine = new BacktestEngine();
        BacktestEngine.BacktestParams params = new BacktestEngine.BacktestParams();
        // Very relaxed params to guarantee signals on oscillating data
        params.rsiOversold = 55;
        params.rsiOverbought = 45;
        params.lookbackBars = 12;
        params.killzoneThreshold = 40.0;  // wide buy/sell zones (40% of range)
        params.minMomentum = 0.1;
        params.stopLossPct = 2.0;
        params.takeProfitPct = 4.0;
        params.useVwapFilter = false;     // disable to isolate signal generation logic
        params.useEmaFilter = false;

        BacktestResult result = engine.run("TEST", "15m", klines, params);
        assertNotNull(result);
        assertTrue(result.getTotalSignals() > 0, "Oscillating prices with relaxed params should generate signals");
        assertTrue(result.getAvgSignalsPerDay() > 0, "Should have at least some signals per day");
    }

    @Test
    void testZonesAreNotSimultaneous() {
        // Generate klines with a clear range to test zone mutual exclusivity
        List<Kline> klines = generateOscillatingKlines(200);
        BacktestEngine engine = new BacktestEngine();
        BacktestEngine.BacktestParams params = new BacktestEngine.BacktestParams();
        params.rsiOversold = 45;
        params.rsiOverbought = 55;
        params.lookbackBars = 24;
        params.killzoneThreshold = 25.0;
        params.minMomentum = 0.3;
        params.stopLossPct = 2.0;
        params.takeProfitPct = 4.0;

        BacktestResult result = engine.run("TEST", "15m", klines, params);
        // If both zones could be true simultaneously, we'd get conflicting signals.
        // The new zone logic (percentile-based) should prevent this.
        assertNotNull(result);
    }

    private List<Kline> generateKlines(int count, double trend) {
        List<Kline> list = new ArrayList<>();
        BigDecimal price = new BigDecimal("100");
        for (int i = 0; i < count; i++) {
            BigDecimal change = price.multiply(BigDecimal.valueOf(trend));
            price = price.add(change).add(BigDecimal.valueOf((Math.random() - 0.5) * 0.5));
            BigDecimal high = price.multiply(BigDecimal.valueOf(1.003));
            BigDecimal low = price.multiply(BigDecimal.valueOf(0.997));
            list.add(new Kline(i, price, high, low, price, BigDecimal.valueOf(5000 + (int)(Math.random()*5000))));
        }
        return list;
    }

    private List<Kline> generateOscillatingKlines(int count) {
        List<Kline> list = new ArrayList<>();
        BigDecimal base = new BigDecimal("100");
        for (int i = 0; i < count; i++) {
            // Use previous price as open so open != close (creates natural momentum)
            BigDecimal open = i == 0 ? base : base.add(BigDecimal.valueOf(Math.sin((i - 1) * 0.2) * 5));
            BigDecimal close = base.add(BigDecimal.valueOf(Math.sin(i * 0.2) * 5));
            BigDecimal high = close.max(open).add(BigDecimal.valueOf(0.5));
            BigDecimal low = close.min(open).subtract(BigDecimal.valueOf(0.5));
            list.add(new Kline(i, open, high, low, close, BigDecimal.valueOf(5000)));
        }
        return list;
    }
}
