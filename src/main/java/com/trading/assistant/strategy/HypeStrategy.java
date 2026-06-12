package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.strategy.model.LinearRegressionChannel;
import com.trading.assistant.strategy.model.PriceProjection;
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

    @Value("${trading.strategy.vwap-band-pct:0.5}")
    private double vwapBandPct;

    @Value("${trading.strategy.use-ema-filter:true}")
    private boolean useEmaFilter;

    @Value("${trading.strategy.ema-period:9}")
    private int emaPeriod;

    @Value("${trading.strategy.ema-extreme-rsi-threshold:15}")
    private double emaExtremeRsiThreshold;

    @Value("${trading.strategy.oversold-spike-rsi-threshold:20}")
    private double oversoldSpikeRsiThreshold;

    @Value("${trading.strategy.oversold-spike-volume-threshold:2.0}")
    private double oversoldSpikeVolumeThreshold;

    @Value("${trading.strategy.atr-period:10}")
    private int atrPeriodForProjection;

    @Value("${trading.strategy.projection-candles-ahead:6}")
    private int projectionCandlesAhead;

    @Value("${trading.strategy.take-profit-pct:1.2}")
    private double takeProfitPctForProjection;

    @Value("${trading.strategy.use-regression-filter:true}")
    private boolean useRegressionFilter;

    @Value("${trading.strategy.regression-lookback:20}")
    private int regressionLookback;

    @Value("${trading.strategy.use-trend-dip-long:true}")
    private boolean useTrendDipLong;

    @Value("${trading.strategy.trend-dip-rsi-threshold:45}")
    private double trendDipRsiThreshold;

    @Value("${trading.strategy.trend-dip-channel-slope:0.02}")
    private double trendDipChannelSlope;

    @Value("${trading.strategy.anti-pump-slope-threshold:0.03}")
    private double antiPumpSlopeThreshold;

    @Value("${trading.strategy.rsi-overbought-uptrend:85}")
    private double rsiOverboughtUptrend;

    @Value("${trading.strategy.short-min-volume-uptrend:2.0}")
    private double shortMinVolumeUptrend;

    @Value("${trading.strategy.short-min-conditions-uptrend:2}")
    private int shortMinConditionsUptrend;

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
            double ema9 = indicatorCalculator.calculateEMAFromKlines(klines, emaPeriod);

            logger.info("Indicators - RSI: {} (prev: {}), Low: {}, High: {}, Momentum: {}%, BuyZone: {}, SellZone: {}, Breakout↑: {}, Breakout↓: {}, Vol: {}x, VWAP: {}, EMA{}: {}",
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
                    String.format("%.4f", vwap),
                    emaPeriod,
                    String.format("%.4f", ema9));

            PriceProjection projection = indicatorCalculator.calculatePriceProjection(
                    klines, atrPeriodForProjection, projectionCandlesAhead, takeProfitPctForProjection);
            if (projection != null) {
                logger.info("📊 {}", projection.toLogString());
            }

            LinearRegressionChannel channel = indicatorCalculator.calculateLinearRegressionChannel(
                    klines, regressionLookback, projectionCandlesAhead);
            if (channel != null) {
                logger.info("{}", channel.toLogString());
            }

            evaluateLongEntry(currentPrice, rsi, previousRsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, breakoutAbove, relativeVolume, marketContext, vwap, ema9, projection, channel);
            evaluateShortEntry(currentPrice, rsi, previousRsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, breakoutBelow, relativeVolume, marketContext, vwap, ema9, projection, channel);

        } catch (Exception e) {
            logger.error("Error executing strategy: {}", e.getMessage(), e);
        }
    }

    private void evaluateLongEntry(BigDecimal currentPrice, double rsi, double previousRsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, boolean breakoutAbove, double relativeVolume, MarketContext ctx, BigDecimal vwap, double ema9, PriceProjection projection, LinearRegressionChannel channel) {
        boolean rsiReversingUp = rsi > previousRsi;
        boolean meanReversionCondition = rsi < rsiOversold && inBuyZone && (!requireRsiReversal || rsiReversingUp);
        boolean extremeOversold = rsi < emaExtremeRsiThreshold;
        boolean volumeSpikeLong = rsi < oversoldSpikeRsiThreshold
                && relativeVolume >= oversoldSpikeVolumeThreshold
                && rsiReversingUp;
        boolean breakoutCondition = breakoutAbove && relativeVolume >= 1.0;

        // Trend-following dip: buy the pullback within an uptrending regression channel
        boolean trendDipCondition = useTrendDipLong
                && channel != null
                && channel.getDirection() == LinearRegressionChannel.ChannelDirection.UP
                && channel.getSlopePct() >= trendDipChannelSlope
                && channel.getPricePosition() < 0.40
                && rsi < trendDipRsiThreshold;

        if (meanReversionCondition || breakoutCondition || trendDipCondition) {
            if (tradeManager.hasOpenPosition("LONG")) {
                logger.info("LONG position already open. Skipping new LONG signal.");
                return;
            }

            // VWAP filter: LONG only within VWAP ± band%
            if (useVwapFilter && vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
                double price = currentPrice.doubleValue();
                double vwapVal = vwap.doubleValue();
                double lower = vwapVal * (1 - vwapBandPct / 100.0);
                double upper = vwapVal * (1 + vwapBandPct / 100.0);
                if (price < lower || price > upper) {
                    logger.info("❌ LONG rejected: price {} outside VWAP band [{}, {}]", String.format("%.4f", price), String.format("%.4f", lower), String.format("%.4f", upper));
                    return;
                }
            }

            // EMA filter: LONG only if price > EMA
            // Exception: skip EMA filter when RSI is extremely oversold (< emaExtremeRsiThreshold)
            if (useEmaFilter && ema9 > 0 && !extremeOversold && currentPrice.doubleValue() <= ema9) {
                logger.info("❌ LONG rejected: price {} below EMA{} {}", String.format("%.4f", currentPrice.doubleValue()), emaPeriod, String.format("%.4f", ema9));
                return;
            }
            if (extremeOversold && currentPrice.doubleValue() <= ema9) {
                logger.info("⚡ EMA filter bypassed: RSI={} < {} (extreme oversold)", String.format("%.2f", rsi), emaExtremeRsiThreshold);
            }

            // Context filters (skipped when contextEnabled=false)
            if (contextEnabled && ctx != null) {
                if (!ctx.supportsLong() && !volumeSpikeLong && !extremeOversold) {
                    logger.info("❌ LONG rejected by market context: trend1h={}, trend4h={}, trend1d={}, BTC={}",
                            ctx.getTrend1h(), ctx.getTrend4h(), ctx.getTrend1d(), ctx.getBtcTrend1d());
                    return;
                }
                if ((volumeSpikeLong || extremeOversold) && !ctx.supportsLong()) {
                    logger.info("⚡ LONG context override: extreme oversold RSI={} (volSpike={})",
                            String.format("%.2f", rsi), volumeSpikeLong);
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

            String entryType;
            if (meanReversionCondition) entryType = "Mean-Reversion";
            else if (breakoutCondition) entryType = "Breakout";
            else entryType = "Trend-Dip";
            logger.info("🟢 LONG SIGNAL DETECTED ({})! RSI: {} (prev: {}), BuyZone: {}, RevUp: {}, Breakout: {}, TrendDip: {}, Volume: {}x, Momentum: {}",
                    entryType, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    inBuyZone, rsiReversingUp, breakoutAbove, trendDipCondition,
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

            // Regression channel filter: for mean-reversion LONG, price should be in lower half of channel
            // Bypassed when extreme oversold or volume spike override is active (capitulation event)
            boolean regressionOverride = extremeOversold || volumeSpikeLong;

            if (useRegressionFilter && channel != null && (meanReversionCondition || trendDipCondition)) {
                if (channel.getPricePosition() > 0.65) {
                    if (regressionOverride) {
                        logger.info("⚡ Regression channel bypassed: price at {}% but extreme signal active (oversold={} volSpike={})",
                                String.format("%.0f", channel.getPricePosition() * 100), extremeOversold, volumeSpikeLong);
                    } else {
                        logger.info("❌ LONG rejected: price at {}% of regression channel (upper zone, need <65%)",
                                String.format("%.0f", channel.getPricePosition() * 100));
                        return;
                    }
                }
                if (channel.getPricePosition() > 0.5) {
                    logger.info("⚠️ LONG warning: price at {}% of regression channel (mid-upper zone)",
                            String.format("%.0f", channel.getPricePosition() * 100));
                }
            }

            enrichSignalWithContext(signal, ctx);
            String note = projection != null ? projection.toAlertString() : "";
            if (channel != null) {
                note = note + (note.isEmpty() ? "" : "\n\n") + channel.toAlertString();
            }
            if (!note.isEmpty()) signal.setProjectionNote(note);
            signalRepository.save(signal);
            tradeManager.executeLongEntry(signal);
        } else {
            logger.info("No LONG signal. MeanRev(RSI<{}:{}, BuyZone:{}, RevUp:{}) Breakout(Above:{}, Vol>1:{}) TrendDip(channelUp:{}, pos<40%:{}, RSI<{}:{})",
                    rsiOversold, rsi < rsiOversold, inBuyZone, rsiReversingUp, breakoutAbove, relativeVolume >= 1.0,
                    channel != null && channel.getDirection() == LinearRegressionChannel.ChannelDirection.UP && channel.getSlopePct() >= trendDipChannelSlope,
                    channel != null && channel.getPricePosition() < 0.40,
                    trendDipRsiThreshold, rsi < trendDipRsiThreshold);
        }
    }

    private void evaluateShortEntry(BigDecimal currentPrice, double rsi, double previousRsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, boolean breakoutBelow, double relativeVolume, MarketContext ctx, BigDecimal vwap, double ema9, PriceProjection projection, LinearRegressionChannel channel) {
        boolean rsiReversingDown = rsi < previousRsi;

        // Dynamic RSI threshold: higher bar when shorting into strong uptrend
        double effectiveRsiOverbought = rsiOverbought;
        boolean strongUptrend = false;
        if (ctx != null && ctx.getTrend1h() == MarketContext.TrendDirection.UP && ctx.getTrend4h() == MarketContext.TrendDirection.UP) {
            effectiveRsiOverbought = rsiOverboughtUptrend;
            strongUptrend = true;
        }

        boolean meanReversionCondition = rsi > effectiveRsiOverbought && inSellZone && (!requireRsiReversal || rsiReversingDown);
        boolean extremeOverbought = rsi > (100 - emaExtremeRsiThreshold);
        boolean volumeSpikeShort = rsi > (100 - oversoldSpikeRsiThreshold)
                && relativeVolume >= oversoldSpikeVolumeThreshold
                && rsiReversingDown;
        boolean breakoutCondition = breakoutBelow && relativeVolume >= 1.0;

        // In strong uptrend, require at least 2 strong conditions (high RSI + high volume or breakout)
        if (strongUptrend && (meanReversionCondition || breakoutCondition)) {
            int strongConditions = 0;
            if (rsi > effectiveRsiOverbought) strongConditions++;
            if (relativeVolume >= shortMinVolumeUptrend) strongConditions++;
            if (breakoutCondition) strongConditions++;
            if (strongConditions < shortMinConditionsUptrend) {
                logger.info("❌ SHORT rejected: only {} strong conditions met in uptrend (need {}). RSI={}, Vol={}x",
                        strongConditions, shortMinConditionsUptrend,
                        String.format("%.2f", rsi), String.format("%.2f", relativeVolume));
                return;
            }
        }

        if (meanReversionCondition || breakoutCondition) {
            if (tradeManager.hasOpenPosition("SHORT")) {
                logger.info("SHORT position already open. Skipping new SHORT signal.");
                return;
            }

            // Anti-pump filter: do not short mean-reversion when regression channel is strongly UP
            if (useRegressionFilter && meanReversionCondition && channel != null
                    && channel.getDirection() == LinearRegressionChannel.ChannelDirection.UP
                    && channel.getSlopePct() >= antiPumpSlopeThreshold) {
                logger.info("❌ SHORT rejected: channel strongly UP (slope: {}%), shorting a pump is dangerous",
                        String.format("%.3f", channel.getSlopePct()));
                return;
            }

            // VWAP filter: SHORT only within VWAP ± band%
            if (useVwapFilter && vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
                double price = currentPrice.doubleValue();
                double vwapVal = vwap.doubleValue();
                double lower = vwapVal * (1 - vwapBandPct / 100.0);
                double upper = vwapVal * (1 + vwapBandPct / 100.0);
                if (price < lower || price > upper) {
                    logger.info("❌ SHORT rejected: price {} outside VWAP band [{}, {}]", String.format("%.4f", price), String.format("%.4f", lower), String.format("%.4f", upper));
                    return;
                }
            }

            // EMA filter: SHORT only if price > EMA (mean-reversion from overbought above EMA)
            // Exception: skip EMA filter when RSI is extremely overbought (> 100 - emaExtremeRsiThreshold)
            if (useEmaFilter && ema9 > 0 && !extremeOverbought && currentPrice.doubleValue() <= ema9) {
                logger.info("❌ SHORT rejected: price {} below EMA{} {}", String.format("%.4f", currentPrice.doubleValue()), emaPeriod, String.format("%.4f", ema9));
                return;
            }
            if (extremeOverbought && currentPrice.doubleValue() <= ema9) {
                logger.info("⚡ EMA filter bypassed: RSI={} > {} (extreme overbought)", String.format("%.2f", rsi), (100 - emaExtremeRsiThreshold));
            }

            // Context filters (skipped when contextEnabled=false)
            if (contextEnabled && ctx != null) {
                if (!ctx.supportsShort() && !volumeSpikeShort) {
                    logger.info("❌ SHORT rejected by market context: trend1h={}, trend4h={}, trend1d={}, BTC={}",
                            ctx.getTrend1h(), ctx.getTrend4h(), ctx.getTrend1d(), ctx.getBtcTrend1d());
                    return;
                }
                if (volumeSpikeShort && !ctx.supportsShort()) {
                    logger.info("⚡ SHORT context override: extreme overbought RSI={} + volume spike {}x (threshold: {}x)",
                            String.format("%.2f", rsi), String.format("%.2f", relativeVolume), oversoldSpikeVolumeThreshold);
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

            // Regression channel filter: for mean-reversion SHORT, price should be in upper half of channel
            // Bypassed when extreme overbought or volume spike override is active (blow-off top event)
            boolean regressionOverrideShort = extremeOverbought || volumeSpikeShort;

            if (useRegressionFilter && channel != null && meanReversionCondition) {
                if (channel.getPricePosition() < 0.35) {
                    if (regressionOverrideShort) {
                        logger.info("⚡ Regression channel bypassed: price at {}% but extreme signal active (overbought={} volSpike={})",
                                String.format("%.0f", channel.getPricePosition() * 100), extremeOverbought, volumeSpikeShort);
                    } else {
                        logger.info("❌ SHORT rejected: price at {}% of regression channel (lower zone, need >35%)",
                                String.format("%.0f", channel.getPricePosition() * 100));
                        return;
                    }
                }
                if (channel.getPricePosition() < 0.5) {
                    logger.info("⚠️ SHORT warning: price at {}% of regression channel (mid-lower zone)",
                            String.format("%.0f", channel.getPricePosition() * 100));
                }
            }

            enrichSignalWithContext(signal, ctx);
            String note = projection != null ? projection.toAlertString() : "";
            if (channel != null) {
                note = note + (note.isEmpty() ? "" : "\n\n") + channel.toAlertString();
            }
            if (!note.isEmpty()) signal.setProjectionNote(note);
            signalRepository.save(signal);
            tradeManager.executeShortEntry(signal);
        } else {
            boolean channelUp = channel != null && channel.getDirection() == LinearRegressionChannel.ChannelDirection.UP
                    && channel.getSlopePct() >= antiPumpSlopeThreshold;
            logger.info("No SHORT signal. MeanRev(RSI>{}:{}, SellZone:{}, RevDown:{}) Breakout(Below:{}, Vol>1:{}) AntiPump(channelUP+strongSlope:{}, slope:{}%){}",
                    effectiveRsiOverbought, rsi > effectiveRsiOverbought, inSellZone, rsiReversingDown, breakoutBelow, relativeVolume >= 1.0,
                    channelUp, channel != null ? String.format("%.3f", channel.getSlopePct()) : "N/A",
                    strongUptrend ? " [uptrend-mode]" : "");
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

    @Scheduled(fixedRate = 10000)
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
