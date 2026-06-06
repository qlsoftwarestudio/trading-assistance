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

    @Scheduled(fixedRate = 900000)
    public void executeStrategy() {
        if (!strategyEnabled) {
            logger.info("Strategy is disabled. Skipping execution.");
            return;
        }

        logger.info("Executing HYPEUSDT 15m LONG+SHORT strategy...");

        try {
            // 1. Market context analysis (multi-timeframe, volume, BTC)
            MarketContext marketContext = null;
            if (contextEnabled) {
                marketContext = marketContextAnalyzer.analyze();
                if (marketContext != null) {
                    logger.info("Market Context: trend1h={}, trend4h={}, trend1d={}, confluence={}, vol={:.2f}x",
                            marketContext.getTrend1h(), marketContext.getTrend4h(), marketContext.getTrend1d(),
                            marketContext.isConfluence(), marketContext.getRelativeVolume());
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
            double sessionLow = indicatorCalculator.calculateSessionLowFromKlines(klines, lookbackBars);
            double sessionHigh = indicatorCalculator.calculateSessionHighFromKlines(klines, lookbackBars);
            double momentum = indicatorCalculator.calculateMomentumFromKlines(klines);
            boolean inBuyZone = indicatorCalculator.isInBuyZone(currentPrice.doubleValue(), sessionLow, killzoneThreshold);
            boolean inSellZone = indicatorCalculator.isInSellZone(currentPrice.doubleValue(), sessionHigh, killzoneThreshold);

            logger.info("Indicators - RSI: {}, Low: {}, High: {}, Momentum: {}%, BuyZone: {}, SellZone: {}",
                    String.format("%.2f", rsi),
                    String.format("%.4f", sessionLow),
                    String.format("%.4f", sessionHigh),
                    String.format("%.2f", momentum),
                    inBuyZone,
                    inSellZone);

            evaluateLongEntry(currentPrice, rsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, marketContext);
            evaluateShortEntry(currentPrice, rsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, marketContext);

        } catch (Exception e) {
            logger.error("Error executing strategy: {}", e.getMessage(), e);
        }
    }

    private void evaluateLongEntry(BigDecimal currentPrice, double rsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, MarketContext ctx) {
        boolean rsiOversoldCondition = rsi < rsiOversold;
        boolean buyZoneCondition = inBuyZone;
        boolean momentumCondition = momentum > minMomentum;

        if (rsiOversoldCondition && buyZoneCondition && momentumCondition) {
            if (tradeManager.hasOpenPosition("LONG")) {
                logger.info("LONG position already open. Skipping new LONG signal.");
                return;
            }

            // Context filters
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
                    logger.info("❌ LONG rejected: volume too low (ratio={:.2f})", ctx.getRelativeVolume());
                    return;
                }
            }

            // Historical performance score
            Signal protoSignal = new Signal(symbol, "LONG", currentPrice,
                    BigDecimal.valueOf(rsi), BigDecimal.valueOf(sessionLow),
                    BigDecimal.valueOf(sessionHigh), BigDecimal.valueOf(momentum), inBuyZone, inSellZone);
            enrichSignalWithContext(protoSignal, ctx);
            double score = signalPerformanceService.scoreSignal(protoSignal);
            if (score < 0.3) {
                logger.info("❌ LONG rejected: poor historical pattern score ({:.2f})", score);
                return;
            } else if (score > 0.7) {
                logger.info("✅ LONG boosted: strong historical pattern score ({:.2f})", score);
            }

            logger.info("🟢 LONG SIGNAL DETECTED! RSI: {}, Buy Zone: {}, Momentum: {}",
                    rsi, inBuyZone, momentum);

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
            logger.debug("No LONG signal. Conditions - RSI Oversold: {}, Buy Zone: {}, Strong Momentum: {}",
                    rsiOversoldCondition, buyZoneCondition, momentumCondition);
        }
    }

    private void evaluateShortEntry(BigDecimal currentPrice, double rsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, MarketContext ctx) {
        boolean rsiOverboughtCondition = rsi > rsiOverbought;
        boolean sellZoneCondition = inSellZone;
        boolean momentumCondition = momentum < -minMomentum;

        if (rsiOverboughtCondition && sellZoneCondition && momentumCondition) {
            if (tradeManager.hasOpenPosition("SHORT")) {
                logger.info("SHORT position already open. Skipping new SHORT signal.");
                return;
            }

            // Context filters
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
                    logger.info("❌ SHORT rejected: volume too low (ratio={:.2f})", ctx.getRelativeVolume());
                    return;
                }
            }

            // Historical performance score
            Signal protoSignal = new Signal(symbol, "SHORT", currentPrice,
                    BigDecimal.valueOf(rsi), BigDecimal.valueOf(sessionLow),
                    BigDecimal.valueOf(sessionHigh), BigDecimal.valueOf(momentum), inBuyZone, inSellZone);
            enrichSignalWithContext(protoSignal, ctx);
            double score = signalPerformanceService.scoreSignal(protoSignal);
            if (score < 0.3) {
                logger.info("❌ SHORT rejected: poor historical pattern score ({:.2f})", score);
                return;
            } else if (score > 0.7) {
                logger.info("✅ SHORT boosted: strong historical pattern score ({:.2f})", score);
            }

            logger.info("🔴 SHORT SIGNAL DETECTED! RSI: {}, Sell Zone: {}, Momentum: {}",
                    rsi, inSellZone, momentum);

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
            logger.debug("No SHORT signal. Conditions - RSI Overbought: {}, Sell Zone: {}, Strong Momentum: {}",
                    rsiOverboughtCondition, sellZoneCondition, momentumCondition);
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

    @Scheduled(fixedRate = 60000)
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
        return String.format("Strategy: HYPEUSDT 15m LONG+SHORT | Enabled: %s | Symbol: %s | " +
                        "RSI(%d) < %.0f / > %.0f | Lookback: %d | Killzone: %.1f%% | Min Momentum: %.1f%% | " +
                        "Context: %s",
                strategyEnabled, symbol, rsiLength, rsiOversold, rsiOverbought,
                lookbackBars, killzoneThreshold, minMomentum, contextEnabled);
    }
}
