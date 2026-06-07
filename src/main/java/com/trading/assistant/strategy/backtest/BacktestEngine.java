package com.trading.assistant.strategy.backtest;

import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.strategy.IndicatorCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Motor de backtesting que simula la estrategia HypeStrategy
 * sobre datos historicos de klines sin look-ahead bias.
 */
public class BacktestEngine {

    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);

    private final IndicatorCalculator calculator = new IndicatorCalculator();

    public BacktestResult run(
            String symbol,
            String timeframe,
            List<Kline> klines,
            BacktestParams params) {

        List<BacktestTrade> trades = new ArrayList<>();
        BacktestPosition position = null;
        int longSignalCount = 0;
        int shortSignalCount = 0;

        int warmup = Math.max(params.rsiLength + 1, Math.max(params.lookbackBars, 50));

        for (int i = warmup; i < klines.size(); i++) {
            List<Kline> window = klines.subList(0, i + 1); // datos disponibles hasta ahora (sin look-ahead)
            Kline current = klines.get(i);

            // 1. Check if open position hit SL or TP
            if (position != null) {
                boolean closed = false;
                BigDecimal exitPrice = null;
                String reason = null;

                if (position.isShort()) {
                    if (current.getHigh().compareTo(position.stopLoss) >= 0) {
                        exitPrice = position.stopLoss;
                        reason = "STOP_LOSS";
                        closed = true;
                    } else if (current.getLow().compareTo(position.takeProfit) <= 0) {
                        exitPrice = position.takeProfit;
                        reason = "TAKE_PROFIT";
                        closed = true;
                    }
                } else {
                    if (current.getLow().compareTo(position.stopLoss) <= 0) {
                        exitPrice = position.stopLoss;
                        reason = "STOP_LOSS";
                        closed = true;
                    } else if (current.getHigh().compareTo(position.takeProfit) >= 0) {
                        exitPrice = position.takeProfit;
                        reason = "TAKE_PROFIT";
                        closed = true;
                    }
                }

                if (closed) {
                    trades.add(new BacktestTrade(
                            position.action,
                            position.entryPrice,
                            position.quantity,
                            exitPrice,
                            reason,
                            position.entryTimestamp,
                            current.getTimestamp()
                    ));
                    position = null;
                }
            }

            // 2. Evaluate entry signals (only if no open position)
            if (position == null) {
                BigDecimal currentPrice = current.getClose();
                double rsi = calculator.calculateRSIFromKlines(window);
                double sessionLow = calculator.calculateSessionLowFromKlines(window, params.lookbackBars);
                double sessionHigh = calculator.calculateSessionHighFromKlines(window, params.lookbackBars);
                double momentum = calculator.calculateMomentumFromKlines(window);

                boolean inBuyZone = calculator.isInBuyZone(currentPrice.doubleValue(), sessionLow, sessionHigh, params.killzoneThreshold);
                boolean inSellZone = calculator.isInSellZone(currentPrice.doubleValue(), sessionLow, sessionHigh, params.killzoneThreshold);

                // LONG entry
                if (rsi < params.rsiOversold && inBuyZone && momentum > params.minMomentum) {
                    longSignalCount++;
                    BigDecimal stopLoss = currentPrice
                            .multiply(BigDecimal.valueOf(1 - params.stopLossPct / 100.0))
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal takeProfit = currentPrice
                            .multiply(BigDecimal.valueOf(1 + params.takeProfitPct / 100.0))
                            .setScale(8, RoundingMode.HALF_UP);

                    position = new BacktestPosition("LONG", currentPrice, stopLoss, takeProfit, current.getTimestamp());
                }

                // SHORT entry
                else if (rsi > params.rsiOverbought && inSellZone && momentum < -params.minMomentum) {
                    shortSignalCount++;
                    BigDecimal stopLoss = currentPrice
                            .multiply(BigDecimal.valueOf(1 + params.stopLossPct / 100.0))
                            .setScale(8, RoundingMode.HALF_UP);
                    BigDecimal takeProfit = currentPrice
                            .multiply(BigDecimal.valueOf(1 - params.takeProfitPct / 100.0))
                            .setScale(8, RoundingMode.HALF_UP);

                    position = new BacktestPosition("SHORT", currentPrice, stopLoss, takeProfit, current.getTimestamp());
                }
            }
        }

        // Close any remaining open position at last close price
        if (position != null && !klines.isEmpty()) {
            Kline last = klines.get(klines.size() - 1);
            trades.add(new BacktestTrade(
                    position.action,
                    position.entryPrice,
                    BigDecimal.ONE, // dummy quantity for P&L calc
                    last.getClose(),
                    "OPEN_AT_END",
                    position.entryTimestamp,
                    last.getTimestamp()
            ));
        }

        long firstTimestamp = klines.isEmpty() ? 0 : klines.get(0).getTimestamp();
        long lastTimestamp = klines.isEmpty() ? 0 : klines.get(klines.size() - 1).getTimestamp();
        BacktestResult result = new BacktestResult(symbol, timeframe, trades, longSignalCount, shortSignalCount, firstTimestamp, lastTimestamp);
        logger.info("Backtest completed: {}", result);
        return result;
    }

    // ============== Inner classes ==============

    public static class BacktestParams {
        public int rsiLength = 5;
        public double rsiOversold = 30.0;
        public double rsiOverbought = 70.0;
        public int lookbackBars = 12;
        public double killzoneThreshold = 1.0;
        public double minMomentum = 0.8;
        public double stopLossPct = 1.0;
        public double takeProfitPct = 3.0;
    }

    private static class BacktestPosition {
        final String action;
        final BigDecimal entryPrice;
        final BigDecimal stopLoss;
        final BigDecimal takeProfit;
        final long entryTimestamp;
        final BigDecimal quantity = BigDecimal.ONE; // simplified

        BacktestPosition(String action, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit, long entryTimestamp) {
            this.action = action;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.entryTimestamp = entryTimestamp;
        }

        boolean isShort() {
            return "SHORT".equals(action);
        }
    }
}
