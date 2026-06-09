package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.execution.TradeManager;
import com.trading.assistant.strategy.model.MarketContext;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class HypeStrategy {

    private static final Logger logger = LoggerFactory.getLogger(HypeStrategy.class);

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Autowired
    private TradeManager tradeManager;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private MarketContextAnalyzer marketContextAnalyzer;

    @Autowired
    private SignalPerformanceService signalPerformanceService;

    @Value("${trading.strategy.enabled:true}")
    private boolean strategyEnabled;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.rsi-length:5}")
    private int rsiLength;

    @Value("${trading.strategy.rsi-oversold:30}")
    private double rsiOversold;

    @Value("${trading.strategy.rsi-overbought:70}")
    private double rsiOverbought;

    @Value("${trading.strategy.lookback-bars:12}")
    private int lookbackBars;

    @Value("${trading.strategy.killzone-threshold:1.0}")
    private double killzoneThreshold;

    @Value("${trading.strategy.min-momentum:0.8}")
    private double minMomentum;

    @Value("${trading.strategy.timeframe:15m}")
    private String timeframe;

    @Value("${trading.context.enabled:true}")
    private boolean contextEnabled;

    @Value("${trading.context.require-confluence:false}")
    private boolean requireConfluence;

    @Value("${trading.context.require-volume:true}")
    private boolean requireVolume;

    @Value("${trading.performance.auto-adjust:false}")
    private boolean autoAdjustEnabled;

    @Value("${trading.strategy.require-rsi-reversal:true}")
    private boolean requireRsiReversal;

    @Value("${trading.strategy.use-vwap-filter:true}")
    private boolean useVwapFilter;

    @Value("${trading.strategy.vwap-period:20}")
    private int vwapPeriod;

    @Scheduled(fixedRate = 120000)
    public void executeStrategy() {
        if (!strategyEnabled) {
            logger.info("Strategy is disabled. Skipping execution.");
            return;
        }

        logger.info("Executing HYPEUSDT 5m SCALPING strategy...");

        try {
            // 1. Market context analysis (multi-timeframe, volume, BTC)
            MarketContext marketContext = null;
            if (contextEnabled) {
                marketContext = marketContextAnalyzer.analyze();
                if (marketContext != null) {
                    logger.info("Market Context: trend1h={}, trend4h={}, trend1d={}, confluence={}, vol={}x",
                            marketContext.getTrend1h(), marketContext.getTrend4h(), marketContext.getTrend1d(),
                            marketContext.isConfluence(), String.format("%.2f", marketContext.getRelativeVolume()));
                }
            }

            // 2. Core technical indicators (15m)
            List<Kline> klines = binanceClient.getKlines(timeframe, 50);

            if (klines == null || klines.isEmpty()) {
                logger.error("No kline data available. Skipping strategy execution.");
                return;
            }

            BigDecimal currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines);
            double rsi = indicatorCalculator.calculateRSIFromKlines(klines);
            double previousRsi = 50.0;
            if (klines.size() > 1) {
                List<Kline> prevKlines = klines.subList(0, klines.size() - 1);
                previousRsi = indicatorCalculator.calculateRSIFromKlines(prevKlines);
            }
            double sessionLow = indicatorCalculator.calculateSessionLowFromKlines(klines, lookbackBars);
            double sessionHigh = indicatorCalculator.calculateSessionHighFromKlines(klines, lookbackBars);
            double momentum = indicatorCalculator.calculateMomentumFromKlines(klines);
            boolean inBuyZone = indicatorCalculator.isInBuyZone(currentPrice.doubleValue(), sessionLow, sessionHigh, killzoneThreshold);
            boolean inSellZone = indicatorCalculator.isInSellZone(currentPrice.doubleValue(), sessionLow, sessionHigh, killzoneThreshold);

            boolean breakoutAbove = indicatorCalculator.isBreakoutAbove(currentPrice.doubleValue(), sessionHigh);
            boolean breakoutBelow = indicatorCalculator.isBreakoutBelow(currentPrice.doubleValue(), sessionLow);
            double relativeVolume = indicatorCalculator.calculateRelativeVolume(klines, lookbackBars);
            int vwapFrom = Math.max(0, klines.size() - vwapPeriod);
            BigDecimal vwap = indicatorCalculator.calculateVWAP(klines.subList(vwapFrom, klines.size()));

            logger.info("Indicators - RSI: {} (prev: {}), Low: {}, High: {}, Momentum: {}%, BuyZone: {}, SellZone: {}, Breakout↑: {}, Breakout↓: {}, Vol: {}x, VWAP: {}",
                    String.format("%.2f", rsi),
                    String.format("%.2f", previousRsi),
                    String.format("%.4f", sessionLow),
                    String.format("%.4f", sessionHigh),
                    String.format("%.2f", momentum),
                    inBuyZone,
                    inSellZone,
                    breakoutAbove,
                    breakoutBelow,
                    String.format("%.2f", relativeVolume),
                    String.format("%.4f", vwap));

            evaluateLongEntry(currentPrice, rsi, previousRsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, breakoutAbove, relativeVolume, marketContext, vwap);
            evaluateShortEntry(currentPrice, rsi, previousRsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, breakoutBelow, relativeVolume, marketContext, vwap);

        } catch (Exception e) {
            logger.error("Error executing strategy: {}", e.getMessage(), e);
        }
    }

    private void evaluateLongEntry(BigDecimal currentPrice, double rsi, double previousRsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, boolean breakoutAbove, double relativeVolume, MarketContext ctx, BigDecimal vwap) {
        boolean rsiReversingUp = rsi > previousRsi;
        boolean meanReversionCondition = rsi < rsiOversold && inBuyZone && (!requireRsiReversal || rsiReversingUp);
        boolean breakoutCondition = breakoutAbove && relativeVolume >= 1.0;

        if (meanReversionCondition || breakoutCondition) {
            if (tradeManager.hasOpenPosition("LONG")) {
                logger.info("LONG position already open. Skipping new LONG signal.");
                return;
            }

            // Context filters (skipped when contextEnabled=false)
            if (contextEnabled && ctx != null) {
                if (!ctx.supportsLong()) {
                    logger.info("❌ LONG rejected by market context: trend1h={}, trend4h={}, trend1d={}, BTC={}",
                            ctx.getTrend1h(), ctx.getTrend4h(), ctx.getTrend1d(), ctx.getBtcTrend1d());
                    return;
                }
                if (requireConfluence && !ctx.isConfluence()) {
                    logger.info("❌ LONG rejected: no trend confluence across timeframes");
                    return;
                }
                if (requireVolume && !marketContextAnalyzer.hasEnoughVolume(ctx)) {
                    logger.info("❌ LONG rejected: volume too low (ratio={})", String.format("%.2f", ctx.getRelativeVolume()));
                    return;
                }
            }

            String entryType = meanReversionCondition ? "Mean-Reversion" : "Breakout";
            logger.info("🟢 LONG SIGNAL DETECTED ({})! RSI: {} (prev: {}), BuyZone: {}, RevUp: {}, Breakout: {}, Volume: {}x, Momentum: {}",
                    entryType, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    inBuyZone, rsiReversingUp, breakoutAbove,
                    String.format("%.2f", relativeVolume), String.format("%.4f", momentum));

            Signal signal = new Signal(
                    symbol,
                    "LONG",
                    currentPrice,
                    BigDecimal.valueOf(rsi),
                    BigDecimal.valueOf(sessionLow),
                    BigDecimal.valueOf(sessionHigh),
                    BigDecimal.valueOf(momentum),
                    inBuyZone,
                    inSellZone
            );

            enrichSignalWithContext(signal, ctx);
            signalRepository.save(signal);
            tradeManager.executeLongEntry(signal);
        } else {
            logger.info("No LONG signal. MeanRev(RSI<{}:{}, BuyZone:{}, RevUp:{}) Breakout(Above:{}, Vol>1:{})",
                    rsiOversold, rsi < rsiOversold, inBuyZone, rsiReversingUp, breakoutAbove, relativeVolume >= 1.0);
        }
    }

    private void evaluateShortEntry(BigDecimal currentPrice, double rsi, double previousRsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, boolean breakoutBelow, double relativeVolume, MarketContext ctx, BigDecimal vwap) {
        boolean rsiReversingDown = rsi < previousRsi;
        boolean meanReversionCondition = rsi > rsiOverbought && inSellZone && (!requireRsiReversal || rsiReversingDown);
        boolean breakoutCondition = breakoutBelow && relativeVolume >= 1.0;

        if (meanReversionCondition || breakoutCondition) {
            if (tradeManager.hasOpenPosition("SHORT")) {
                logger.info("SHORT position already open. Skipping new SHORT signal.");
                return;
            }

            // Context filters (skipped when contextEnabled=false)
            if (contextEnabled && ctx != null) {
                if (!ctx.supportsShort()) {
                    logger.info("❌ SHORT rejected by market context: trend1h={}, trend4h={}, trend1d={}, BTC={}",
                            ctx.getTrend1h(), ctx.getTrend4h(), ctx.getTrend1d(), ctx.getBtcTrend1d());
                    return;
                }
                if (requireConfluence && !ctx.isConfluence()) {
                    logger.info("❌ SHORT rejected: no trend confluence across timeframes");
                    return;
                }
                if (requireVolume && !marketContextAnalyzer.hasEnoughVolume(ctx)) {
                    logger.info("❌ SHORT rejected: volume too low (ratio={})", String.format("%.2f", ctx.getRelativeVolume()));
                    return;
                }
            }

            String entryType = meanReversionCondition ? "Mean-Reversion" : "Breakout";
            logger.info("🔴 SHORT SIGNAL DETECTED ({})! RSI: {} (prev: {}), SellZone: {}, RevDown: {}, Breakout: {}, Volume: {}x, Momentum: {}",
                    entryType, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    inSellZone, rsiReversingDown, breakoutBelow,
                    String.format("%.2f", relativeVolume), String.format("%.4f", momentum));

            Signal signal = new Signal(
                    symbol,
                    "SHORT",
                    currentPrice,
                    BigDecimal.valueOf(rsi),
                    BigDecimal.valueOf(sessionLow),
                    BigDecimal.valueOf(sessionHigh),
                    BigDecimal.valueOf(momentum),
                    inBuyZone,
                    inSellZone
            );

            enrichSignalWithContext(signal, ctx);
            signalRepository.save(signal);
            tradeManager.executeShortEntry(signal);
        } else {
            logger.info("No SHORT signal. MeanRev(RSI>{}:{}, SellZone:{}, RevDown:{}) Breakout(Below:{}, Vol>1:{})",
                    rsiOverbought, rsi > rsiOverbought, inSellZone, rsiReversingDown, breakoutBelow, relativeVolume >= 1.0);
        }
    }

    private void enrichSignalWithContext(Signal signal, MarketContext ctx) {
        if (ctx == null) return;
        signal.setTrend1h(ctx.getTrend1h() != null ? ctx.getTrend1h().name() : null);
        signal.setTrend4h(ctx.getTrend4h() != null ? ctx.getTrend4h().name() : null);
        signal.setTrend1d(ctx.getTrend1d() != null ? ctx.getTrend1d().name() : null);
        signal.setRelativeVolume(BigDecimal.valueOf(ctx.getRelativeVolume()));
        signal.setBtcCorrelation(BigDecimal.valueOf(ctx.getBtcCorrelation()));
        signal.setBtcTrend1d(ctx.getBtcTrend1d() != null ? ctx.getBtcTrend1d().name() : null);
        signal.setConfluence(ctx.isConfluence());
        signal.setDistanceToSupportPct(BigDecimal.valueOf(ctx.getDistanceToSupportPct()));
        signal.setDistanceToResistancePct(BigDecimal.valueOf(ctx.getDistanceToResistancePct()));
    }

    public void executeStrategyManual() {
        logger.info("Manual strategy execution triggered");
        executeStrategy();
    }

    @Scheduled(fixedRate = 30000)
    public void monitorOpenTrades() {
        if (!strategyEnabled) {
            return;
        }
        try {
            tradeManager.monitorAndCloseTrades();
        } catch (Exception e) {
            logger.error("Error monitoring trades: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 4 * * *") // Every day at 4 AM
    public void autoAdjustThresholds() {
        if (!autoAdjustEnabled) {
            return;
        }
        try {
            logger.info("Running auto-adjustment of strategy thresholds...");
            signalPerformanceService.refreshPatternStats();
            SignalPerformanceService.ThresholdAdjustments adjustments = signalPerformanceService.suggestAdjustments();
            if (adjustments != null) {
                logger.info("📊 Suggested adjustments: RSI oversold={:.1f}, minMomentum={:.2f}",
                        adjustments.suggestedRsiOversold, adjustments.suggestedMinMomentum);
                // Aplicar ajustes conservadores (max 5% delta)
                double deltaRsi = Math.abs(adjustments.suggestedRsiOversold - rsiOversold);
                double deltaMomentum = Math.abs(adjustments.suggestedMinMomentum - minMomentum);
                if (deltaRsi < 5.0) {
                    rsiOversold = adjustments.suggestedRsiOversold;
                }
                if (deltaMomentum < 0.5) {
                    minMomentum = adjustments.suggestedMinMomentum;
                }
                logger.info("✅ Thresholds updated: rsiOversold={:.1f}, minMomentum={:.2f}", rsiOversold, minMomentum);
            } else {
                logger.info("No adjustments suggested (insufficient data)");
            }
        } catch (Exception e) {
            logger.error("Error during auto-adjustment: {}", e.getMessage(), e);
        }
    }

    public String getStrategyStatus() {
        return String.format("Strategy: HYPEUSDT 5m SCALPING | Enabled: %s | Symbol: %s | " +
                        "RSI(%d) < %.0f / > %.0f | Lookback: %d | Killzone: %.1f%% | Min Momentum: %.1f%% | " +
                        "Context: %s",
                strategyEnabled, symbol, rsiLength, rsiOversold, rsiOverbought,
                lookbackBars, killzoneThreshold, minMomentum, contextEnabled);
    }
}
