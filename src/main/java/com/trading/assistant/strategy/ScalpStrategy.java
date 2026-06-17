package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.execution.TradeManager;
import com.trading.assistant.strategy.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hunter / Scalping strategy using 1m candles.
 * Only activates when market conditions are optimal (high vol, tight spread, volume spike).
 * Runs independently from the 5m swing strategy.
 *
 * Entry logic: catch micro-reversals or momentum continuation on 1m timeframe.
 * Exit: tight SL/TP with ultra-fast trailing stop.
 */
@Component
public class ScalpStrategy {

    private static final Logger logger = LoggerFactory.getLogger(ScalpStrategy.class);

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Autowired
    private TradeManager tradeManager;

    @Autowired
    private MarketConditionGate marketConditionGate;

    @Value("${trading.strategy.hunter.mode-enabled:false}")
    private boolean hunterModeEnabled;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.hunter.rsi-oversold:25}")
    private double rsiOversold;

    @Value("${trading.strategy.hunter.rsi-overbought:75}")
    private double rsiOverbought;

    @Value("${trading.strategy.hunter.momentum-threshold:0.05}")
    private double momentumThreshold;

    @Value("${trading.strategy.hunter.vwap-proximity-pct:0.3}")
    private double vwapProximityPct;

    @Value("${trading.strategy.hunter.rsi-period:7}")
    private int rsiPeriod;

    @Value("${trading.strategy.hunter.vwap-period:14}")
    private int vwapPeriod;

    @Value("${trading.strategy.hunter.ema-period:9}")
    private int emaPeriod;

    @Value("${trading.strategy.hunter.lookback:20}")
    private int lookbackBars;

    /**
     * Execute scalp strategy every 15 seconds (4x per 1m candle).
     * Only runs if hunter mode is enabled and market conditions pass the gate.
     */
    @Scheduled(fixedRate = 15000)
    public void executeScalpStrategy() {
        if (!hunterModeEnabled) {
            logger.trace("Hunter mode disabled. Skipping scalp strategy.");
            return;
        }

        logger.debug("🎯 Executing HYPEUSDT 1m SCALPING strategy...");

        try {
            // Fetch 1m klines (need enough for RSI, VWAP, EMA, ATR)
            List<Kline> klines1m = binanceClient.getKlines(symbol, "1m", lookbackBars + 30);

            if (klines1m == null || klines1m.size() < lookbackBars + 10) {
                logger.warn("Insufficient 1m klines ({}). Skipping scalp.", klines1m == null ? 0 : klines1m.size());
                return;
            }

            // Calculate 1m indicators
            BigDecimal currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines1m);
            double rsi = indicatorCalculator.calculateRSIFromKlines(klines1m); // Uses last 5 closes
            double previousRsi = 50.0;
            if (klines1m.size() > 2) {
                List<Kline> prevKlines = klines1m.subList(0, klines1m.size() - 1);
                previousRsi = indicatorCalculator.calculateRSIFromKlines(prevKlines);
            }
            double momentum = indicatorCalculator.calculateMomentumFromKlines(klines1m);
            double sessionLow = indicatorCalculator.calculateSessionLowFromKlines(klines1m, lookbackBars);
            double sessionHigh = indicatorCalculator.calculateSessionHighFromKlines(klines1m, lookbackBars);
            boolean inBuyZone = indicatorCalculator.isInBuyZone(currentPrice.doubleValue(), sessionLow, sessionHigh, 30.0);
            boolean inSellZone = indicatorCalculator.isInSellZone(currentPrice.doubleValue(), sessionLow, sessionHigh, 30.0);

            int vwapFrom = Math.max(0, klines1m.size() - vwapPeriod);
            BigDecimal vwap = indicatorCalculator.calculateVWAP(klines1m.subList(vwapFrom, klines1m.size()));
            double ema = indicatorCalculator.calculateEMAFromKlines(klines1m, emaPeriod);

            // Distance to VWAP as %
            double vwapDistancePct = 0.0;
            if (vwap.compareTo(BigDecimal.ZERO) > 0) {
                vwapDistancePct = Math.abs(currentPrice.subtract(vwap).doubleValue()) / vwap.doubleValue() * 100.0;
            }

            logger.info("🎯 Scalp indicators (1m): Price={}, RSI={} (prev={}), Mo={}%, BuyZone={}, SellZone={}, VWAP={}, EMA={}, VWAPdist={}%",
                    currentPrice, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    String.format("%.3f", momentum), inBuyZone, inSellZone, vwap, ema, String.format("%.3f", vwapDistancePct));

            // Evaluate scalp entries — gate checks per-direction capacity
            evaluateScalpLongEntry(currentPrice, rsi, previousRsi, momentum, inBuyZone, vwap, vwapDistancePct, ema, klines1m);
            evaluateScalpShortEntry(currentPrice, rsi, previousRsi, momentum, inSellZone, vwap, vwapDistancePct, ema, klines1m);

        } catch (Exception e) {
            logger.error("Error executing scalp strategy: {}", e.getMessage(), e);
        }
    }

    private void evaluateScalpLongEntry(BigDecimal currentPrice, double rsi, double previousRsi,
                                         double momentum, boolean inBuyZone, BigDecimal vwap,
                                         double vwapDistancePct, double ema, List<Kline> klines1m) {
        // Per-direction gate check
        if (!marketConditionGate.canScalp(klines1m, "LONG")) {
            return;
        }

        // Condition 1: RSI oversold micro
        boolean rsiOversoldMicro = rsi <= rsiOversold;
        // Condition 2: RSI reversing up (current > previous)
        boolean rsiReversingUp = rsi > previousRsi;
        // Condition 3: Momentum positive (current candle closing up)
        boolean momentumPositive = momentum >= momentumThreshold;
        // Condition 4: Price near VWAP (mean reversion target)
        boolean nearVwap = vwapDistancePct <= vwapProximityPct;
        // Condition 5: Price above EMA (micro trend aligned)
        boolean aboveEma = currentPrice.doubleValue() >= ema;
        // Condition 6: Volume spike
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, lookbackBars);
        boolean volumeSpike = volRatio >= 1.5;

        boolean meanRevLong = rsiOversoldMicro && rsiReversingUp && momentumPositive;
        boolean vwapBounce = inBuyZone && nearVwap && momentumPositive && aboveEma;

        if (meanRevLong || vwapBounce) {
            String entryType = meanRevLong ? "SCALP_MEAN_REVERSION" : "SCALP_VWAP_BOUNCE";
            logger.info("🟢 SCALP LONG signal: {} | RSI={}→{}, Mo={}%, nearVwap={}, vol={}x",
                    entryType, String.format("%.2f", previousRsi), String.format("%.2f", rsi),
                    String.format("%.3f", momentum), nearVwap, String.format("%.2f", volRatio));
            tradeManager.executeScalpLongEntry(currentPrice, entryType, rsi, momentum, volRatio);
        } else {
            logger.debug("No scalp LONG. MeanRev(RSI<{}:{}, RevUp:{}, Mo>{}:{}), VwapBounce(inBuy:{}, nearVwap:{}, aboveEma:{}, vol>1.5:{})",
                    rsiOversold, rsiOversoldMicro, rsiReversingUp, momentumThreshold, momentumPositive,
                    inBuyZone, nearVwap, aboveEma, volumeSpike);
        }
    }

    private void evaluateScalpShortEntry(BigDecimal currentPrice, double rsi, double previousRsi,
                                          double momentum, boolean inSellZone, BigDecimal vwap,
                                          double vwapDistancePct, double ema, List<Kline> klines1m) {
        // Per-direction gate check
        if (!marketConditionGate.canScalp(klines1m, "SHORT")) {
            return;
        }

        // Condition 1: RSI overbought micro
        boolean rsiOverboughtMicro = rsi >= rsiOverbought;
        // Condition 2: RSI reversing down
        boolean rsiReversingDown = rsi < previousRsi;
        // Condition 3: Momentum negative
        boolean momentumNegative = momentum <= -momentumThreshold;
        // Condition 4: Price near VWAP
        boolean nearVwap = vwapDistancePct <= vwapProximityPct;
        // Condition 5: Price below EMA (micro trend aligned)
        boolean belowEma = currentPrice.doubleValue() <= ema;
        // Condition 6: Volume spike
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, lookbackBars);
        boolean volumeSpike = volRatio >= 1.5;

        boolean meanRevShort = rsiOverboughtMicro && rsiReversingDown && momentumNegative;
        boolean vwapRejection = inSellZone && nearVwap && momentumNegative && belowEma;

        if (meanRevShort || vwapRejection) {
            String entryType = meanRevShort ? "SCALP_MEAN_REVERSION" : "SCALP_VWAP_REJECTION";
            logger.info("🔴 SCALP SHORT signal: {} | RSI={}→{}, Mo={}%, nearVwap={}, vol={}x",
                    entryType, String.format("%.2f", previousRsi), String.format("%.2f", rsi),
                    String.format("%.3f", momentum), nearVwap, String.format("%.2f", volRatio));
            tradeManager.executeScalpShortEntry(currentPrice, entryType, rsi, momentum, volRatio);
        } else {
            logger.debug("No scalp SHORT. MeanRev(RSI>{}:{}, RevDown:{}, Mo<-{}:{}), VwapReject(inSell:{}, nearVwap:{}, belowEma:{}, vol>1.5:{})",
                    rsiOverbought, rsiOverboughtMicro, rsiReversingDown, momentumThreshold, momentumNegative,
                    inSellZone, nearVwap, belowEma, volumeSpike);
        }
    }
}
