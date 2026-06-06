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
        params.rsiOversold = 30;
        params.rsiOverbought = 70;
        params.lookbackBars = 12;
        params.killzoneThreshold = 2.0;
        params.minMomentum = 0.1;
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
        params.rsiOversold = 40;           // relaxed
        params.rsiOverbought = 60;         // relaxed
        params.lookbackBars = 12;
        params.killzoneThreshold = 5.0;    // wider zone
        params.minMomentum = 0.05;         // very relaxed
        params.stopLossPct = 2.0;
        params.takeProfitPct = 4.0;

        BacktestResult result = engine.run("TEST", "15m", klines, params);
        assertNotNull(result);
        // Oscillating prices with relaxed params should generate some trades
        assertTrue(result.getTotalTrades() > 0, "Oscillating prices should generate some signals");
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
            BigDecimal price = base.add(BigDecimal.valueOf(Math.sin(i * 0.2) * 5));
            BigDecimal high = price.add(BigDecimal.valueOf(0.5));
            BigDecimal low = price.subtract(BigDecimal.valueOf(0.5));
            list.add(new Kline(i, price, high, low, price, BigDecimal.valueOf(5000)));
        }
        return list;
    }
}
