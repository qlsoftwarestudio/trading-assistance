package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.strategy.model.LinearRegressionChannel;
import com.trading.assistant.strategy.model.PriceProjection;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.execution.TradeManager;
import com.trading.assistant.notification.TelegramBot;
import com.trading.assistant.strategy.model.MarketContext;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import com.trading.assistant.user.model.Bot;
import com.trading.assistant.user.repository.BotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    @Autowired
    private AutoAdjustService autoAdjustService;

    @Autowired
    private BotRepository botRepository;

    @Autowired
    private TelegramBot telegramBot;

    @Autowired
    private com.trading.assistant.portfolio.repository.RejectedSignalRepository rejectedSignalRepository;

    @Value("${trading.strategy.enabled:true}")
    private boolean strategyEnabled;

    private volatile boolean running = true;

    @Value("${trading.strategy.symbols:HYPEUSDT}")
    private List<String> symbols;

    private final Set<String> disabledSymbols = ConcurrentHashMap.newKeySet();

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

    @Value("${trading.strategy.position-size-pct:10.0}")
    private double positionSizePct;

    @Value("${trading.strategy.leverage:5}")
    private int leverage;

    @Value("${trading.strategy.stop-loss-pct:0.6}")
    private double stopLossPct;

    @Value("${trading.strategy.take-profit-pct:1.2}")
    private double takeProfitPct;

    @Value("${trading.strategy.max-concurrent-trades:1}")
    private int maxConcurrentTrades;

    @Value("${trading.strategy.max-hold-minutes:45}")
    private int maxHoldMinutes;

    @Value("${trading.strategy.trailing-stop-pct:0.6}")
    private double trailingStopPct;

    @Value("${trading.strategy.trailing-activation-pct:0.6}")
    private double trailingActivationPct;

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

    // Delta volume filter: estimate buy/sell pressure from candle close position
    @Value("${trading.strategy.use-delta-volume-filter:true}")
    private boolean useDeltaVolumeFilter;

    @Value("${trading.strategy.delta-volume-threshold:0.20}")
    private double deltaVolumeThreshold;

    @Value("${trading.strategy.anti-pump-slope-threshold:0.03}")
    private double antiPumpSlopeThreshold;

    @Value("${trading.strategy.anti-crash-slope-threshold:0.03}")
    private double antiCrashSlopeThreshold;

    @Value("${trading.strategy.use-stoch-bb-filter:false}")
    private boolean useStochBbFilter;

    @Value("${trading.strategy.stoch-period:14}")
    private int stochPeriod;

    @Value("${trading.strategy.stoch-smooth-k:3}")
    private int stochSmoothK;

    @Value("${trading.strategy.stoch-smooth-d:3}")
    private int stochSmoothD;

    @Value("${trading.strategy.stoch-oversold:20}")
    private double stochOversoldThreshold;

    @Value("${trading.strategy.stoch-overbought:80}")
    private double stochOverboughtThreshold;

    @Value("${trading.strategy.bb-period:20}")
    private int bbPeriod;

    @Value("${trading.strategy.bb-std-dev:2.0}")
    private double bbStdDev;

    @Value("${trading.strategy.bb-proximity-pct:1.0}")
    private double bbProximityPct;

    @Value("${trading.strategy.rsi-overbought-uptrend:85}")
    private double rsiOverboughtUptrend;

    @Value("${trading.strategy.short-min-volume-uptrend:2.0}")
    private double shortMinVolumeUptrend;

    @Value("${trading.strategy.short-min-conditions-uptrend:2}")
    private int shortMinConditionsUptrend;

    @Value("${trading.strategy.use-trend1h-mean-rev-filter:true}")
    private boolean useTrend1hMeanRevFilter;

    // Balance filter: reject breakouts when market is consolidating (chop)
    @Value("${trading.strategy.use-balance-filter:true}")
    private boolean useBalanceFilter;

    @Value("${trading.strategy.balance-lookback-short:20}")
    private int balanceLookbackShort;

    @Value("${trading.strategy.balance-lookback-long:50}")
    private int balanceLookbackLong;

    @Value("${trading.strategy.balance-atr-compression-ratio:0.30}")
    private double balanceAtrCompressionRatio;

    // Absorption filter: high volume + small candle range = institutional footprint
    @Value("${trading.strategy.use-absorption-filter:false}")
    private boolean useAbsorptionFilter;

    @Value("${trading.strategy.absorption-volume-multiplier:2.0}")
    private double absorptionVolumeMultiplier;

    @Value("${trading.strategy.absorption-range-multiplier:0.3}")
    private double absorptionRangeMultiplier;

    @Value("${trading.session-filter.enabled:true}")
    private boolean sessionFilterEnabled;

    @Value("${trading.session-filter.asia-start:2}")
    private int asiaStartHour;

    @Value("${trading.session-filter.asia-end:8}")
    private int asiaEndHour;

    @Scheduled(fixedRate = 120000)
    public void executeStrategy() {
        if (!strategyEnabled) {
            logger.info("Strategy is disabled via config. Skipping execution.");
            return;
        }
        if (!running) {
            logger.debug("Strategy is paused (toggle OFF). Skipping execution.");
            return;
        }

        // Session filter: skip Asian session (low volume, wide spreads)
        if (sessionFilterEnabled && isAsianSession()) {
            logger.info("Asian session ({}:00-{}:00 UTC) — strategy paused.", asiaStartHour, asiaEndHour);
            return;
        }

        List<Bot> activeBots = botRepository.findByEnabledTrueAndRunningTrueWithUser();
        if (activeBots.isEmpty()) {
            logger.debug("No active bots found in DB. Strategy idle.");
            return;
        }
        for (Bot bot : activeBots) {
            String sym = bot.getSymbol();
            Long userId = bot.getUser() != null ? bot.getUser().getId() : 1L;
            logger.info("▶ Executing strategy for bot '{}' (userId={}, symbol={})", bot.getName(), userId, sym);
            executeStrategyForSymbol(sym, userId);
        }
    }

    private void executeStrategyForSymbol(String sym, Long userId) {
        logger.info("Executing {} 5m swing strategy...", sym);

        try {
            // 1. Market context analysis (multi-timeframe, volume, BTC)
            MarketContext marketContext = null;
            if (contextEnabled) {
                marketContext = marketContextAnalyzer.analyze(sym);
                if (marketContext != null) {
                    logger.info("Market Context: trend1h={}, trend4h={}, trend1d={}, confluence={}, vol={}x",
                            marketContext.getTrend1h(), marketContext.getTrend4h(), marketContext.getTrend1d(),
                            marketContext.isConfluence(), String.format("%.2f", marketContext.getRelativeVolume()));
                }
            }

            // 2. Core technical indicators (15m)
            List<Kline> klines = binanceClient.getKlines(sym, timeframe, 50);

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

            Kline currentKline = klines.get(klines.size() - 1);

            // Balance filter: detect consolidation/chop (Fabio: 70% of time market is in balance)
            boolean marketInBalance = false;
            if (useBalanceFilter) {
                marketInBalance = indicatorCalculator.isMarketInBalance(klines, balanceLookbackShort, balanceLookbackLong, balanceAtrCompressionRatio);
                logger.info("📊 Market Balance (ATR{}/ATR{} < {}): {}", balanceLookbackShort, balanceLookbackLong, balanceAtrCompressionRatio, marketInBalance);
            }

            // Absorption filter: high volume + small range = institutional footprint
            boolean absorptionDetected = false;
            if (useAbsorptionFilter) {
                absorptionDetected = indicatorCalculator.detectAbsorption(currentKline, klines, absorptionVolumeMultiplier, absorptionRangeMultiplier);
                logger.info("📊 Absorption (vol>{}×avg, range<{}×ATR): {}", absorptionVolumeMultiplier, absorptionRangeMultiplier, absorptionDetected);
            }

            // Estimate buy/sell pressure from current candle
            double deltaVolume = indicatorCalculator.estimateVolumeDelta(currentKline);
            double deltaVolumeRatio = 0;
            if (currentKline != null && currentKline.getVolume() != null && currentKline.getVolume().compareTo(BigDecimal.ZERO) > 0) {
                deltaVolumeRatio = deltaVolume / currentKline.getVolume().doubleValue();
            }
            if (useDeltaVolumeFilter) {
                String pressure = deltaVolumeRatio > 0.20 ? "BUY_DOMINANT"
                        : (deltaVolumeRatio < -0.20 ? "SELL_DOMINANT" : "NEUTRAL");
                logger.info("📊 Delta volume: {} (ratio: {}) - Pressure: {}",
                        String.format("%.2f", deltaVolume),
                        String.format("%.2f", deltaVolumeRatio),
                        pressure);
            }

            // Stochastic Oscillator + Bollinger Bands (5m current TF + 15m filter)
            double[] stoch5m = indicatorCalculator.calculateStochastic(klines, stochPeriod, stochSmoothK, stochSmoothD);
            double[] bb5m    = indicatorCalculator.calculateBollingerBands(klines, bbPeriod, bbStdDev);
            double stochK5m = stoch5m[0], stochD5m = stoch5m[1];
            double bbUpper = bb5m[0], bbMid = bb5m[1], bbLower = bb5m[2];

            int klines15mCount = stochPeriod + stochSmoothK + stochSmoothD + 5;
            List<Kline> klines15m = binanceClient.getKlines(sym, "15m", klines15mCount);
            double[] stoch15m = indicatorCalculator.calculateStochastic(klines15m, stochPeriod, stochSmoothK, stochSmoothD);
            double stochK15m = stoch15m[0];

            if (useStochBbFilter) {
                logger.info("📈 Stoch+BB | %K5m={} %D5m={} %K15m={} | BB upper={} mid={} lower={}",
                        String.format("%.1f", stochK5m), String.format("%.1f", stochD5m),
                        String.format("%.1f", stochK15m),
                        String.format("%.4f", bbUpper), String.format("%.4f", bbMid), String.format("%.4f", bbLower));
            }

            evaluateLongEntry(currentPrice, rsi, previousRsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, breakoutAbove, relativeVolume, marketContext, vwap, ema9, projection, channel, deltaVolumeRatio, stochK5m, stochD5m, stochK15m, bbUpper, bbMid, bbLower, sym, userId, marketInBalance, absorptionDetected);
            evaluateShortEntry(currentPrice, rsi, previousRsi, sessionLow, sessionHigh, momentum, inBuyZone, inSellZone, breakoutBelow, relativeVolume, marketContext, vwap, ema9, projection, channel, deltaVolumeRatio, stochK5m, stochD5m, stochK15m, bbUpper, bbMid, bbLower, sym, userId, marketInBalance, absorptionDetected);

            // Update trailing stops and time exits for open trades (data already fetched above)
            tradeManager.updateTrailingAndTimeExit(sym, userId, currentPrice, currentKline, projection);

        } catch (Exception e) {
            // Capture stack trace BEFORE touching any Spring beans (shutdown-safe)
            String stackTrace = java.util.Arrays.toString(e.getStackTrace());
            String safeStack = stackTrace.substring(0, Math.min(800, stackTrace.length()));
            logger.error("Error executing strategy: {} | Stack: {}", e.getMessage(), safeStack);

            // Guard: if Spring context is shutting down, skip Telegram to avoid bean-creation errors
            try {
                if (telegramBot != null) {
                    telegramBot.sendAlert("⚠️ Error en HypeStrategy",
                            "Error: " + e.getMessage() + "\n<code>" + safeStack + "</code>");
                }
            } catch (Exception te) {
                logger.warn("Failed to send Telegram alert during error handling: {}", te.getMessage());
            }
        }
    }

    private void evaluateLongEntry(BigDecimal currentPrice, double rsi, double previousRsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, boolean breakoutAbove, double relativeVolume, MarketContext ctx, BigDecimal vwap, double ema9, PriceProjection projection, LinearRegressionChannel channel, double deltaVolumeRatio, double stochK5m, double stochD5m, double stochK15m, double bbUpper, double bbMid, double bbLower, String sym, Long userId, boolean marketInBalance, boolean absorptionDetected) {
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

        // Balance filter: invalidate breakout when market is in consolidation/chop
        if (useBalanceFilter && marketInBalance && breakoutCondition) {
            logger.info("❌ LONG Breakout rejected: market in balance (consolidation/chop) — breakouts fail 70% of time here");
            saveRejection(sym, "LONG", "Breakout", "BALANCE_FILTER", currentPrice, rsi, momentum, 0.0);
            breakoutCondition = false;
        }

        // Absorption filter: for mean-reversion, require institutional footprint at key level
        if (useAbsorptionFilter && meanReversionCondition && !absorptionDetected && !volumeSpikeLong && !extremeOversold) {
            logger.info("❌ LONG Mean-Reversion rejected: no absorption detected at this level (vol not spiking with small range)");
            saveRejection(sym, "LONG", "Mean-Reversion", "ABSORPTION_FILTER", currentPrice, rsi, momentum, 0.0);
            meanReversionCondition = false;
        }

        if (meanReversionCondition || breakoutCondition || trendDipCondition) {
            if (tradeManager.hasOpenPosition(sym, userId, "LONG")) {
                logger.info("LONG position already open for userId={} symbol={}. Skipping.", userId, sym);
                return;
            }

            // Volume filter: reject mean-reversion LONG when volume is too low (no buying pressure)
            if (meanReversionCondition && relativeVolume < 0.5 && !volumeSpikeLong) {
                logger.info("❌ LONG rejected: volume too low for mean-reversion ({}x, need >= 0.5x). RSI={}, no buying pressure.",
                        String.format("%.2f", relativeVolume), String.format("%.2f", rsi));
                saveRejection(sym, "LONG", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                        "VOLUME_LOW", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // VWAP filter: LONG only within VWAP ± band%
            // Hierarchy: allow override when Stoch+BB confirms extreme oversold (price near BB lower + stoch < 20)
            if (useVwapFilter && vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
                double price = currentPrice.doubleValue();
                double vwapVal = vwap.doubleValue();
                double lower = vwapVal * (1 - vwapBandPct / 100.0);
                double upper = vwapVal * (1 + vwapBandPct / 100.0);
                if (price < lower || price > upper) {
                    boolean stochExtremeOversold = stochK5m < stochOversoldThreshold;
                    double lowerBandGap = indicatorCalculator.getBBDistancePct(price, bbLower);
                    boolean nearLowerBand = lowerBandGap <= bbProximityPct && lowerBandGap >= -1.0;
                    if (useStochBbFilter && stochExtremeOversold && nearLowerBand) {
                        logger.info("⚡ VWAP filter bypassed: Stoch+BB extreme oversold (stochK5m={} < {}, lowerBandGap={}%)",
                                String.format("%.1f", stochK5m), stochOversoldThreshold, String.format("%.2f", lowerBandGap));
                    } else {
                        logger.info("❌ LONG rejected: price {} outside VWAP band [{}, {}]", String.format("%.4f", price), String.format("%.4f", lower), String.format("%.4f", upper));
                        saveRejection(sym, "LONG", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                                "VWAP_FILTER", currentPrice, rsi, momentum, Math.abs(price - vwapVal) / vwapVal * 100.0);
                        return;
                    }
                }
            }

            // EMA filter: LONG only if price > EMA
            // Exception: skip EMA filter when RSI is extremely oversold (< emaExtremeRsiThreshold)
            if (useEmaFilter && ema9 > 0 && !extremeOversold && currentPrice.doubleValue() <= ema9) {
                logger.info("❌ LONG rejected: price {} below EMA{} {}", String.format("%.4f", currentPrice.doubleValue()), emaPeriod, String.format("%.4f", ema9));
                saveRejection(sym, "LONG", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                        "EMA_FILTER", currentPrice, rsi, momentum, 0.0);
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
                    saveRejection(sym, "LONG", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                            "CONTEXT_FILTER", currentPrice, rsi, momentum, 0.0);
                    return;
                }
                if ((volumeSpikeLong || extremeOversold) && !ctx.supportsLong()) {
                    logger.info("⚡ LONG context override: extreme oversold RSI={} (volSpike={})",
                            String.format("%.2f", rsi), volumeSpikeLong);
                }
                if (requireConfluence && !ctx.isConfluence()) {
                    logger.info("❌ LONG rejected: no trend confluence across timeframes");
                    saveRejection(sym, "LONG", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                            "NO_CONFLUENCE", currentPrice, rsi, momentum, 0.0);
                    return;
                }
                if (requireVolume && !marketContextAnalyzer.hasEnoughVolume(ctx)) {
                    logger.info("❌ LONG rejected: volume too low (ratio={})", String.format("%.2f", ctx.getRelativeVolume()));
                    saveRejection(sym, "LONG", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                            "CONTEXT_VOLUME_LOW", currentPrice, rsi, momentum, 0.0);
                    return;
                }
            }

            String entryType;
            if (meanReversionCondition) entryType = "Mean-Reversion";
            else if (breakoutCondition) entryType = "Breakout";
            else entryType = "Trend-Dip";

            // Delta volume filter: for LONG, require net buying pressure (except breakouts which imply it)
            if (useDeltaVolumeFilter && !breakoutCondition && deltaVolumeRatio < deltaVolumeThreshold) {
                logger.info("❌ LONG {} rejected: sell pressure dominant (delta ratio: {}, need > {}).",
                        entryType, String.format("%.2f", deltaVolumeRatio), String.format("%.2f", deltaVolumeThreshold));
                saveRejection(sym, "LONG", entryType, "DELTA_VOLUME_FILTER", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // Stoch+BB confirmation filter (optional, config-gated)
            if (useStochBbFilter) {
                boolean stochOversold5m = stochK5m < stochOversoldThreshold;
                double price = currentPrice.doubleValue();
                double lowerBandGap = indicatorCalculator.getBBDistancePct(price, bbLower); // >0 means price above lower band
                boolean nearLowerBand = lowerBandGap <= bbProximityPct && lowerBandGap >= -1.0; // within bbProximityPct% above or below lower band
                boolean stoch15mOk = stochK15m < stochOverboughtThreshold; // 15m not overbought
                if (!(stochOversold5m && nearLowerBand && stoch15mOk)) {
                    logger.info("❌ LONG rejected by Stoch+BB: stochK5m={} (need <{}) lowerBandGap={}% (need <{}%) stochK15m={} (need <{})",
                            String.format("%.1f", stochK5m), stochOversoldThreshold,
                            String.format("%.2f", lowerBandGap), bbProximityPct,
                            String.format("%.1f", stochK15m), stochOverboughtThreshold);
                    saveRejection(sym, "LONG", entryType, "STOCH_BB_FILTER", currentPrice, rsi, momentum, 0.0);
                    return;
                }
                logger.info("✅ Stoch+BB LONG confirmed: stochK5m={} lowerBandGap={}% stochK15m={}",
                        String.format("%.1f", stochK5m), String.format("%.2f", lowerBandGap), String.format("%.1f", stochK15m));
            }

            // Auto-adjust: skip if this setup has been disabled due to poor performance
            if (!autoAdjustService.isSetupEnabled("LONG", entryType)) {
                logger.info("🚫 LONG {} entry blocked by auto-adjust (poor recent performance).", entryType);
                saveRejection(sym, "LONG", entryType, "AUTO_ADJUST_BLOCKED", currentPrice, rsi, momentum, 0.0);
                return;
            }

            logger.info("🟢 LONG SIGNAL DETECTED ({})! RSI: {} (prev: {}), BuyZone: {}, RevUp: {}, Breakout: {}, TrendDip: {}, Volume: {}x, Momentum: {}",
                    entryType, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    inBuyZone, rsiReversingUp, breakoutAbove, trendDipCondition,
                    String.format("%.2f", relativeVolume), String.format("%.4f", momentum));

            Signal signal = new Signal(
                    sym,
                    "LONG",
                    currentPrice,
                    BigDecimal.valueOf(rsi),
                    BigDecimal.valueOf(sessionLow),
                    BigDecimal.valueOf(sessionHigh),
                    BigDecimal.valueOf(momentum),
                    inBuyZone,
                    inSellZone
            );
            signal.setSetupType(entryType);
            signal.setUserId(userId);
            if (bbLower > 0) {
                signal.setBbLower(BigDecimal.valueOf(bbLower).setScale(8, java.math.RoundingMode.HALF_UP));
                signal.setBbMid(BigDecimal.valueOf(bbMid).setScale(8, java.math.RoundingMode.HALF_UP));
                signal.setBbUpper(BigDecimal.valueOf(bbUpper).setScale(8, java.math.RoundingMode.HALF_UP));
            }
            signal.setStochK5m(stochK5m);
            signal.setStochD5m(stochD5m);

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
                        saveRejection(sym, "LONG", entryType, "REGRESSION_FILTER", currentPrice, rsi, momentum, 0.0);
                        return;
                    }
                }
                if (channel.getPricePosition() > 0.5) {
                    logger.info("⚠️ LONG warning: price at {}% of regression channel (mid-upper zone)",
                            String.format("%.0f", channel.getPricePosition() * 100));
                }
            }

            // Anti-Crash filter: do not buy when regression channel is strongly DOWN
            boolean channelDown = useRegressionFilter && channel != null
                    && channel.getDirection() == LinearRegressionChannel.ChannelDirection.DOWN
                    && channel.getSlopePct() <= -antiCrashSlopeThreshold;
            if (channelDown && !extremeOversold) {
                logger.info("❌ LONG rejected: channel strongly DOWN (slope: {}%), buying a crash is dangerous",
                        String.format("%.3f", channel.getSlopePct()));
                saveRejection(sym, "LONG", entryType, "ANTI_CRASH_FILTER", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // Trend-Dip in downtrend filter: avoid dip-buying when daily trend is DOWN
            if (trendDipCondition && ctx != null && ctx.getTrend1d() == MarketContext.TrendDirection.DOWN) {
                logger.info("❌ LONG rejected: trend1d is DOWN, avoiding dip-buying in bearish daily (RSI: {})",
                        String.format("%.2f", rsi));
                saveRejection(sym, "LONG", entryType, "TREND_DIP_DOWNTREND", currentPrice, rsi, momentum, 0.0);
                return;
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
            boolean channelDownReject = useRegressionFilter && channel != null
                    && channel.getDirection() == LinearRegressionChannel.ChannelDirection.DOWN
                    && channel.getSlopePct() <= -antiCrashSlopeThreshold;
            boolean trend1dDownReject = ctx != null && ctx.getTrend1d() == MarketContext.TrendDirection.DOWN;
            logger.info("No LONG signal. MeanRev(RSI<{}:{}, BuyZone:{}, RevUp:{}) Breakout(Above:{}, Vol>1:{}) TrendDip(channelUp:{}, pos<40%:{}, RSI<{}:{}, t1dDown:{}) AntiCrash(channelDown:{})",
                    rsiOversold, rsi < rsiOversold, inBuyZone, rsiReversingUp, breakoutAbove, relativeVolume >= 1.0,
                    channel != null && channel.getDirection() == LinearRegressionChannel.ChannelDirection.UP && channel.getSlopePct() >= trendDipChannelSlope,
                    channel != null && channel.getPricePosition() < 0.40,
                    trendDipRsiThreshold, rsi < trendDipRsiThreshold, trend1dDownReject,
                    channelDownReject);
        }
    }

    private void evaluateShortEntry(BigDecimal currentPrice, double rsi, double previousRsi, double sessionLow, double sessionHigh, double momentum, boolean inBuyZone, boolean inSellZone, boolean breakoutBelow, double relativeVolume, MarketContext ctx, BigDecimal vwap, double ema9, PriceProjection projection, LinearRegressionChannel channel, double deltaVolumeRatio, double stochK5m, double stochD5m, double stochK15m, double bbUpper, double bbMid, double bbLower, String sym, Long userId, boolean marketInBalance, boolean absorptionDetected) {
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

        // Trend-following pullback: short the bounce within a downtrending regression channel
        boolean trendDipShortCondition = useTrendDipLong
                && channel != null
                && channel.getDirection() == LinearRegressionChannel.ChannelDirection.DOWN
                && channel.getSlopePct() <= -trendDipChannelSlope
                && channel.getPricePosition() > 0.60
                && rsi > (100 - trendDipRsiThreshold);

        // In strong uptrend, require at least 2 strong conditions (high RSI + high volume or breakout)
        // Trend-dip is excluded from uptrend protection (shorting into downtrend is the point)
        if (strongUptrend && (meanReversionCondition || breakoutCondition) && !trendDipShortCondition) {
            int strongConditions = 0;
            if (rsi > effectiveRsiOverbought) strongConditions++;
            if (relativeVolume >= shortMinVolumeUptrend) strongConditions++;
            if (breakoutCondition) strongConditions++;
            if (strongConditions < shortMinConditionsUptrend) {
                logger.info("❌ SHORT rejected: only {} strong conditions met in uptrend (need {}). RSI={}, Vol={}x",
                        strongConditions, shortMinConditionsUptrend,
                        String.format("%.2f", rsi), String.format("%.2f", relativeVolume));
                saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                        "UPTREND_CONDITIONS", currentPrice, rsi, momentum, 0.0);
                return;
            }
        }

        // Balance filter: invalidate breakout when market is in consolidation/chop
        if (useBalanceFilter && marketInBalance && breakoutCondition) {
            logger.info("❌ SHORT Breakout rejected: market in balance (consolidation/chop) — breakouts fail 70% of time here");
            saveRejection(sym, "SHORT", "Breakout", "BALANCE_FILTER", currentPrice, rsi, momentum, 0.0);
            breakoutCondition = false;
        }

        // Absorption filter: for mean-reversion, require institutional footprint at key level
        if (useAbsorptionFilter && meanReversionCondition && !absorptionDetected && !volumeSpikeShort && !extremeOverbought) {
            logger.info("❌ SHORT Mean-Reversion rejected: no absorption detected at this level (vol not spiking with small range)");
            saveRejection(sym, "SHORT", "Mean-Reversion", "ABSORPTION_FILTER", currentPrice, rsi, momentum, 0.0);
            meanReversionCondition = false;
        }

        if (meanReversionCondition || breakoutCondition || trendDipShortCondition) {
            if (tradeManager.hasOpenPosition(sym, userId, "SHORT")) {
                logger.info("SHORT position already open for userId={} symbol={}. Skipping.", userId, sym);
                return;
            }

            // Volume filter: reject mean-reversion SHORT when volume is too low (no selling pressure)
            if (meanReversionCondition && relativeVolume < 0.5 && !volumeSpikeShort) {
                logger.info("❌ SHORT rejected: volume too low for mean-reversion ({}x, need >= 0.5x). RSI={}, no selling pressure.",
                        String.format("%.2f", relativeVolume), String.format("%.2f", rsi));
                saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                        "VOLUME_LOW", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // Anti-pump filter: do not short mean-reversion when regression channel is strongly UP
            if (useRegressionFilter && meanReversionCondition && channel != null
                    && channel.getDirection() == LinearRegressionChannel.ChannelDirection.UP
                    && channel.getSlopePct() >= antiPumpSlopeThreshold) {
                logger.info("❌ SHORT rejected: channel strongly UP (slope: {}%), shorting a pump is dangerous",
                        String.format("%.3f", channel.getSlopePct()));
                saveRejection(sym, "SHORT", "Mean-Reversion", "ANTI_PUMP_FILTER", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // 1h trend filter for Mean-Reversion SHORT:
            // If 1h trend is UP and price is above VWAP, reject — shorting a pump above VWAP is too risky
            if (useTrend1hMeanRevFilter && meanReversionCondition && !volumeSpikeShort
                    && ctx != null && ctx.getTrend1h() == MarketContext.TrendDirection.UP
                    && vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0
                    && currentPrice.compareTo(vwap) > 0) {
                logger.info("❌ SHORT rejected: Mean-Rev but trend1h=UP and price {} > VWAP {} — avoid shorting a pump above VWAP",
                        String.format("%.4f", currentPrice.doubleValue()), String.format("%.4f", vwap.doubleValue()));
                saveRejection(sym, "SHORT", "Mean-Reversion", "TREND1H_MEANREV_FILTER", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // VWAP filter: SHORT only within VWAP ± band%
            // Hierarchy: allow override when Stoch+BB confirms extreme overbought (price near BB upper + stoch > 80)
            if (useVwapFilter && vwap != null && vwap.compareTo(BigDecimal.ZERO) > 0) {
                double price = currentPrice.doubleValue();
                double vwapVal = vwap.doubleValue();
                double lower = vwapVal * (1 - vwapBandPct / 100.0);
                double upper = vwapVal * (1 + vwapBandPct / 100.0);
                if (price < lower || price > upper) {
                    boolean stochExtremeOverbought = stochK5m > stochOverboughtThreshold;
                    double upperBandGap = indicatorCalculator.getBBDistancePct(price, bbUpper);
                    boolean nearUpperBand = upperBandGap >= -bbProximityPct && upperBandGap <= 1.0;
                    if (useStochBbFilter && stochExtremeOverbought && nearUpperBand) {
                        logger.info("⚡ VWAP filter bypassed: Stoch+BB extreme overbought (stochK5m={} > {}, upperBandGap={}%)",
                                String.format("%.1f", stochK5m), stochOverboughtThreshold, String.format("%.2f", upperBandGap));
                    } else {
                        logger.info("❌ SHORT rejected: price {} outside VWAP band [{}, {}]", String.format("%.4f", price), String.format("%.4f", lower), String.format("%.4f", upper));
                        saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                                "VWAP_FILTER", currentPrice, rsi, momentum, Math.abs(price - vwapVal) / vwapVal * 100.0);
                        return;
                    }
                }
            }

            // EMA filter: SHORT only if price > EMA (mean-reversion from overbought above EMA)
            // Exception: skip EMA filter when RSI is extremely overbought (> 100 - emaExtremeRsiThreshold)
            if (useEmaFilter && ema9 > 0 && !extremeOverbought && currentPrice.doubleValue() <= ema9) {
                logger.info("❌ SHORT rejected: price {} below EMA{} {}", String.format("%.4f", currentPrice.doubleValue()), emaPeriod, String.format("%.4f", ema9));
                saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                        "EMA_FILTER", currentPrice, rsi, momentum, 0.0);
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
                    saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                            "CONTEXT_FILTER", currentPrice, rsi, momentum, 0.0);
                    return;
                }
                if (volumeSpikeShort && !ctx.supportsShort()) {
                    logger.info("⚡ SHORT context override: extreme overbought RSI={} + volume spike {}x (threshold: {}x)",
                            String.format("%.2f", rsi), String.format("%.2f", relativeVolume), oversoldSpikeVolumeThreshold);
                }
                if (requireConfluence && !ctx.isConfluence()) {
                    logger.info("❌ SHORT rejected: no trend confluence across timeframes");
                    saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                            "NO_CONFLUENCE", currentPrice, rsi, momentum, 0.0);
                    return;
                }
                if (requireVolume && !marketContextAnalyzer.hasEnoughVolume(ctx)) {
                    logger.info("❌ SHORT rejected: volume too low (ratio={})", String.format("%.2f", ctx.getRelativeVolume()));
                    saveRejection(sym, "SHORT", meanReversionCondition ? "Mean-Reversion" : (breakoutCondition ? "Breakout" : "Trend-Dip"),
                            "CONTEXT_VOLUME_LOW", currentPrice, rsi, momentum, 0.0);
                    return;
                }
            }

            // Trend-Dip in uptrend filter: avoid shorting bounces when daily trend is UP
            if (trendDipShortCondition && ctx != null && ctx.getTrend1d() == MarketContext.TrendDirection.UP) {
                logger.info("❌ SHORT rejected: trend1d is UP, avoiding shorting bounce in bullish daily (RSI: {})",
                        String.format("%.2f", rsi));
                saveRejection(sym, "SHORT", "Trend-Dip", "TREND_DIP_UPTREND", currentPrice, rsi, momentum, 0.0);
                return;
            }

            String entryType;
            if (meanReversionCondition) entryType = "Mean-Reversion";
            else if (breakoutCondition) entryType = "Breakout";
            else entryType = "Trend-Dip";

            // Delta volume filter: for SHORT, require net selling pressure (except breakouts which imply it)
            if (useDeltaVolumeFilter && !breakoutCondition && deltaVolumeRatio > -deltaVolumeThreshold) {
                logger.info("❌ SHORT {} rejected: buy pressure dominant (delta ratio: {}, need < -{}).",
                        entryType, String.format("%.2f", deltaVolumeRatio), String.format("%.2f", deltaVolumeThreshold));
                saveRejection(sym, "SHORT", entryType, "DELTA_VOLUME_FILTER", currentPrice, rsi, momentum, 0.0);
                return;
            }

            // Stoch+BB confirmation filter (optional, config-gated)
            if (useStochBbFilter) {
                boolean stochOverbought5m = stochK5m > stochOverboughtThreshold;
                double price = currentPrice.doubleValue();
                double upperBandGap = indicatorCalculator.getBBDistancePct(price, bbUpper); // <0 means price below upper band
                boolean nearUpperBand = upperBandGap >= -bbProximityPct && upperBandGap <= 1.0; // within bbProximityPct% below or above upper band
                boolean stoch15mOk = stochK15m > stochOversoldThreshold; // 15m not oversold
                if (!(stochOverbought5m && nearUpperBand && stoch15mOk)) {
                    logger.info("❌ SHORT rejected by Stoch+BB: stochK5m={} (need >{}) upperBandGap={}% (need >-{}%) stochK15m={} (need >{})",
                            String.format("%.1f", stochK5m), stochOverboughtThreshold,
                            String.format("%.2f", upperBandGap), bbProximityPct,
                            String.format("%.1f", stochK15m), stochOversoldThreshold);
                    saveRejection(sym, "SHORT", entryType, "STOCH_BB_FILTER", currentPrice, rsi, momentum, 0.0);
                    return;
                }
                logger.info("✅ Stoch+BB SHORT confirmed: stochK5m={} upperBandGap={}% stochK15m={}",
                        String.format("%.1f", stochK5m), String.format("%.2f", upperBandGap), String.format("%.1f", stochK15m));
            }

            // Auto-adjust: skip if this setup has been disabled due to poor performance
            if (!autoAdjustService.isSetupEnabled("SHORT", entryType)) {
                logger.info("🚫 SHORT {} entry blocked by auto-adjust (poor recent performance).", entryType);
                saveRejection(sym, "SHORT", entryType, "AUTO_ADJUST_BLOCKED", currentPrice, rsi, momentum, 0.0);
                return;
            }

            logger.info("🔴 SHORT SIGNAL DETECTED ({})! RSI: {} (prev: {}), SellZone: {}, RevDown: {}, Breakout: {}, TrendDip: {}, Volume: {}x, Momentum: {}",
                    entryType, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    inSellZone, rsiReversingDown, breakoutBelow, trendDipShortCondition,
                    String.format("%.2f", relativeVolume), String.format("%.4f", momentum));

            Signal signal = new Signal(
                    sym,
                    "SHORT",
                    currentPrice,
                    BigDecimal.valueOf(rsi),
                    BigDecimal.valueOf(sessionLow),
                    BigDecimal.valueOf(sessionHigh),
                    BigDecimal.valueOf(momentum),
                    inBuyZone,
                    inSellZone
            );
            signal.setSetupType(entryType);
            signal.setUserId(userId);
            if (bbUpper > 0) {
                signal.setBbLower(BigDecimal.valueOf(bbLower).setScale(8, java.math.RoundingMode.HALF_UP));
                signal.setBbMid(BigDecimal.valueOf(bbMid).setScale(8, java.math.RoundingMode.HALF_UP));
                signal.setBbUpper(BigDecimal.valueOf(bbUpper).setScale(8, java.math.RoundingMode.HALF_UP));
            }
            signal.setStochK5m(stochK5m);
            signal.setStochD5m(stochD5m);

            // Regression channel filter: for mean-reversion SHORT, price should be in upper half of channel
            // Bypassed when extreme overbought or volume spike override is active (blow-off top event)
            boolean regressionOverrideShort = extremeOverbought || volumeSpikeShort;

            if (useRegressionFilter && channel != null && (meanReversionCondition || trendDipShortCondition)) {
                if (channel.getPricePosition() < 0.35) {
                    if (regressionOverrideShort) {
                        logger.info("⚡ Regression channel bypassed: price at {}% but extreme signal active (overbought={} volSpike={})",
                                String.format("%.0f", channel.getPricePosition() * 100), extremeOverbought, volumeSpikeShort);
                    } else {
                        logger.info("❌ SHORT rejected: price at {}% of regression channel (lower zone, need >35%)",
                                String.format("%.0f", channel.getPricePosition() * 100));
                        saveRejection(sym, "SHORT", entryType, "REGRESSION_FILTER", currentPrice, rsi, momentum, 0.0);
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
            logger.info("No SHORT signal. MeanRev(RSI>{}:{}, SellZone:{}, RevDown:{}) Breakout(Below:{}, Vol>1:{}) TrendDip(channelDown:{}, pos>60%:{}, RSI>{}:{}) AntiPump(channelUP+strongSlope:{}, slope:{}%){}",
                    effectiveRsiOverbought, rsi > effectiveRsiOverbought, inSellZone, rsiReversingDown, breakoutBelow, relativeVolume >= 1.0,
                    channel != null && channel.getDirection() == LinearRegressionChannel.ChannelDirection.DOWN && channel.getSlopePct() <= -trendDipChannelSlope,
                    channel != null && channel.getPricePosition() > 0.60,
                    100 - trendDipRsiThreshold, rsi > (100 - trendDipRsiThreshold),
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

    /**
     * Check if current time is within Asian trading session.
     * Asian session (02:00-08:00 UTC) typically has low volume and wide spreads.
     */
    private boolean isAsianSession() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int hour = now.getHour();
        return hour >= asiaStartHour && hour < asiaEndHour;
    }

    public void executeStrategyManual() {
        logger.info("Manual strategy execution triggered");
        executeStrategy();
    }

    // monitorOpenTrades polling removed — Binance User Data Stream (WebSocket) now handles SL/TP execution.
    // Trailing stop and time exit are updated within executeStrategy() using already-fetched data.
    public void monitorOpenTrades() {
        // Kept for manual/debug invocation only. Scheduled polling eliminated.
        logger.debug("monitorOpenTrades() called manually — no-op since WS handles real-time execution.");
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
            telegramBot.sendAlert("⚠️ Error en auto-adjust",
                    "Auto-adjust falló: " + e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (!strategyEnabled) {
            throw new IllegalStateException("Cannot start: strategy is disabled in configuration");
        }
        this.running = true;
        logger.info("Strategy STARTED (toggle ON)");
    }

    public void stop() {
        this.running = false;
        logger.info("Strategy STOPPED (toggle OFF)");
    }

    public boolean toggle() {
        if (!strategyEnabled) {
            throw new IllegalStateException("Cannot toggle: strategy is disabled in configuration");
        }
        this.running = !this.running;
        logger.info("Strategy toggled: running={}", this.running);
        return this.running;
    }

    public void disableSymbol(String sym) {
        disabledSymbols.add(sym);
        logger.info("Symbol {} DISABLED via bot toggle.", sym);
    }

    public void enableSymbol(String sym) {
        disabledSymbols.remove(sym);
        logger.info("Symbol {} ENABLED via bot toggle.", sym);
    }

    public boolean isSymbolEnabled(String sym) {
        return !disabledSymbols.contains(sym);
    }

    // Phase 3.1: Rejection tracking helper
    private void saveRejection(String symbol, String action, String setupType, String reason,
                               BigDecimal price, double rsi, double momentum, double vwapDist) {
        try {
            if (rejectedSignalRepository != null) {
                rejectedSignalRepository.save(new com.trading.assistant.portfolio.model.RejectedSignal(
                        symbol, action, "HYPE", setupType, reason,
                        price, BigDecimal.valueOf(rsi), BigDecimal.valueOf(momentum), BigDecimal.valueOf(vwapDist)));
            }
        } catch (Exception e) {
            logger.debug("Failed to save rejection: {}", e.getMessage());
        }
    }

    public String getStrategyStatus() {
        return String.format("Swing Multi-Pair: %s | Enabled: %s | Running: %s | Disabled: %s |\n" +
                        "Pos: %.0f%% | Lev: %dx | SL: %.1f%% | TP: %.1f%% | MaxTrades: %d | MaxHold: %dm |\n" +
                        "RSI(%d) < %.0f / > %.0f | Lookback: %d | Killzone: %.1f%% | MinMom: %.1f%% |\n" +
                        "Trail: %.1f%% (act %.1f%%) | Context: %s",
                symbols, strategyEnabled, running, disabledSymbols,
                positionSizePct, leverage, stopLossPct, takeProfitPct, maxConcurrentTrades, maxHoldMinutes,
                rsiLength, rsiOversold, rsiOverbought, lookbackBars, killzoneThreshold, minMomentum,
                trailingStopPct, trailingActivationPct, contextEnabled);
    }
}
