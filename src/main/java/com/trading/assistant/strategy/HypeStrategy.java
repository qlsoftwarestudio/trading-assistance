package com.trading.assistant.strategy;

import com.trading.assistant.binance.ExchangeClient;
import com.trading.assistant.strategy.model.LinearRegressionChannel;
import com.trading.assistant.strategy.model.PriceProjection;
import com.trading.assistant.strategy.model.RangeBreakoutResult;
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
    private ExchangeClient binanceClient;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Autowired
    private TradeManager tradeManager;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private MarketContextAnalyzer marketContextAnalyzer;

    @Autowired
    private MarketStructureAnalyzer marketStructureAnalyzer;

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

    @Value("${trading.strategy.use-ema-filter:false}")
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

    @Value("${trading.strategy.use-breakout:true}")
    private boolean useBreakout;

    @Value("${trading.strategy.use-trend-dip-long:true}")
    private boolean useTrendDipLong;

    @Value("${trading.strategy.trend-dip-rsi-threshold:45}")
    private double trendDipRsiThreshold;

    @Value("${trading.strategy.trend-dip-channel-slope:0.02}")
    private double trendDipChannelSlope;

    // Delta volume filter: estimate buy/sell pressure from candle close position
    @Value("${trading.strategy.use-delta-volume-filter:true}")
    private boolean useDeltaVolumeFilter;

    @Value("${trading.strategy.delta-volume-threshold:0.10}")
    private double deltaVolumeThreshold;

    @Value("${trading.strategy.anti-pump-slope-threshold:0.03}")
    private double antiPumpSlopeThreshold;

    @Value("${trading.strategy.anti-crash-slope-threshold:0.03}")
    private double antiCrashSlopeThreshold;

    @Value("${trading.strategy.use-stoch-bb-filter:true}")
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

    @Value("${trading.strategy.stoch-15m-long-max:50}")
    private double stoch15mLongMax;

    @Value("${trading.strategy.stoch-15m-short-min:50}")
    private double stoch15mShortMin;

    @Value("${trading.strategy.block-short-on-4h-uptrend:true}")
    private boolean blockShortOn4hUptrend;

    @Value("${trading.strategy.ema200-filter-symbols:}")
    private String ema200FilterSymbols;

    @Value("${trading.strategy.symbol-bb-period:}")
    private String symbolBbPeriodConfig;

    @Value("${trading.strategy.bb-period:20}")
    private int bbPeriod;

    @Value("${trading.strategy.bb-std-dev:2.0}")
    private double bbStdDev;

    @Value("${trading.strategy.bb-proximity-pct:1.0}")
    private double bbProximityPct;

    @Value("${trading.strategy.rsi-overbought-uptrend:85}")
    private double rsiOverboughtUptrend;

    // Rejection candle filter: require a wick-based reversal candle at BB extremes
    @Value("${trading.strategy.use-rejection-candle-filter:false}")
    private boolean useRejectionCandleFilter;

    @Value("${trading.strategy.rejection-candle-min-wick-ratio:1.0}")
    private double rejectionCandleMinWickRatio;

    @Value("${trading.strategy.rejection-candle-bypass-rsi:74.0}")
    private double rejectionCandleBypassRsi;

    @Value("${trading.strategy.stoch-overbought-macro-down:70.0}")
    private double stochOverboughtMacroDown;

    @Value("${trading.strategy.ema200-long-bypass-rsi:20.0}")
    private double ema200LongBypassRsi;

    // Liquidity sweep filter: detect price sweeping a recent swing high/low with long wick + close back
    @Value("${trading.strategy.use-liquidity-sweep-filter:false}")
    private boolean useLiquiditySweepFilter;

    @Value("${trading.strategy.liquidity-sweep-lookback:5}")
    private int liquiditySweepLookback;

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

    @Value("${trading.strategy.absorption-volume-multiplier:1.5}")
    private double absorptionVolumeMultiplier;

    @Value("${trading.strategy.absorption-range-multiplier:0.5}")
    private double absorptionRangeMultiplier;

    @Value("${trading.session-filter.enabled:true}")
    private boolean sessionFilterEnabled;

    @Value("${trading.session-filter.asia-start:2}")
    private int asiaStartHour;

    @Value("${trading.session-filter.asia-end:8}")
    private int asiaEndHour;

    // Per-symbol active session (UTC hours). Format: "EURUSDT:8-17,XAGUSDT:8-21"
    // If a symbol has no entry, it trades 24/7 (no session restriction).
    @Value("${trading.strategy.symbol-session-utc:}")
    private String symbolSessionConfig;

    // Per-symbol RSI threshold overrides. Format: "EURUSDT:35,XAGUSDT:30"
    @Value("${trading.strategy.symbol-rsi-oversold:}")
    private String symbolRsiOversoldConfig;

    @Value("${trading.strategy.symbol-rsi-overbought:}")
    private String symbolRsiOverboughtConfig;

    @Value("${trading.strategy.use-range-breakout:true}")
    private boolean useRangeBreakout;

    @Value("${trading.strategy.breakout-lookback:20}")
    private int breakoutLookback;

    @Value("${trading.strategy.breakout-max-range-pct:2.5}")
    private double breakoutMaxRangePct;

    @Value("${trading.strategy.breakout-volume-multiplier:2.5}")
    private double breakoutVolumeMultiplier;

    // ── HTF (H1) macro structure filter ──────────────────────────────────────────
    // Blocks M5 SHORTs when H1 structure is BULLISH, and M5 LONGs when H1 is BEARISH.
    @Value("${trading.strategy.use-htf-structure-filter:false}")
    private boolean useHtfStructureFilter;

    @Value("${trading.strategy.htf-timeframe:1h}")
    private String htfTimeframe;

    @Value("${trading.strategy.htf-klines:120}")
    private int htfKlines;

    @Value("${trading.strategy.htf-pivot-strength:3}")
    private int htfPivotStrength;

    // ── Order Block filter (institutional zones) ─────────────────────────────────
    // The M5 BB/RSI trigger only fires when price is inside a valid demand/supply OB.
    @Value("${trading.strategy.use-order-block-filter:false}")
    private boolean useOrderBlockFilter;

    @Value("${trading.strategy.ob-pivot-strength:2}")
    private int obPivotStrength;

    @Value("${trading.strategy.ob-displacement-atr:1.5}")
    private double obDisplacementAtr;

    @Value("${trading.strategy.ob-max-blocks:5}")
    private int obMaxBlocks;

    // ── Pure SMC entry mode ──────────────────────────────────────────────────────
    // Entries are driven ONLY by SMC structure: a valid Order Block aligned with the
    // H1 macro structure, inside a kill zone, with a structural SL and >=1:2 R:R
    // (enforced in TradeManager). All legacy filters (BB, Stochastic, RSI, VWAP, EMA,
    // rejection candle, liquidity sweep, breakout) have been removed.

    @Scheduled(fixedRate = 60000)
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
        // Per-symbol session gate: skip if outside configured trading hours
        if (!isSymbolInSession(sym)) {
            logger.info("⏰ {} outside active session (UTC) — skipping.", sym);
            return;
        }

        // Temporarily apply per-symbol RSI thresholds (restored after evaluate)
        double savedRsiOversold   = rsiOversold;
        double savedRsiOverbought = rsiOverbought;
        rsiOversold   = getSymbolRsiOversold(sym);
        rsiOverbought = getSymbolRsiOverbought(sym);

        try {
            logger.info("Executing {} 5m swing strategy... [rsiOversold={}, rsiOverbought={}]", sym,
                    String.format("%.0f", rsiOversold), String.format("%.0f", rsiOverbought));
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

            // 2. Core technical indicators (5m) — fetch 100 to cover BB(50) + regression(50) + buffer
            List<Kline> klines = binanceClient.getKlines(sym, timeframe, 100);

            if (klines == null || klines.isEmpty()) {
                logger.error("No kline data available. Skipping strategy execution.");
                return;
            }

            BigDecimal currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines);
            logger.info("💱 {} price={}", sym, String.format("%.5f", currentPrice.doubleValue()));

            // Price projection (ATR-based) — used only for trailing-stop / time-exit management
            PriceProjection projection = indicatorCalculator.calculatePriceProjection(
                    klines, atrPeriodForProjection, projectionCandlesAhead, takeProfitPctForProjection);
            if (projection != null) {
                logger.info("📊 {}", projection.toLogString());
            }

            Kline currentKline = klines.get(klines.size() - 1);

            // ── SMC filters: HTF (H1) macro structure + Order Block ─────────────────
            // Direction is set by the H1 macro structure; the trigger is price entering a
            // valid Order Block (demand for LONG, supply for SHORT).
            boolean allowLong = true;
            boolean allowShort = true;
            boolean inBullOB = true;  // defaults to true when OB filter is disabled
            boolean inBearOB = true;

            if (useHtfStructureFilter) {
                List<Kline> htf = binanceClient.getKlines(sym, htfTimeframe, htfKlines);
                MarketStructureAnalyzer.Structure htfStructure =
                        marketStructureAnalyzer.analyzeMacroStructure(htf, htfPivotStrength);
                logger.info("🏗️ HTF({}) macro structure: {}", htfTimeframe, htfStructure);
                if (htfStructure == MarketStructureAnalyzer.Structure.BULLISH) {
                    allowShort = false; // do not short into a bullish macro structure
                } else if (htfStructure == MarketStructureAnalyzer.Structure.BEARISH) {
                    allowLong = false;  // do not buy into a bearish macro structure
                } else {
                    // Pure SMC requires a directional H1 bias — skip ranging/neutral structure.
                    allowLong = false;
                    allowShort = false;
                }
            }

            if (useOrderBlockFilter) {
                double obAtr = indicatorCalculator.calculateATR(klines, atrPeriodForProjection);
                List<MarketStructureAnalyzer.OrderBlock> obs =
                        marketStructureAnalyzer.findOrderBlocks(klines, obPivotStrength, obDisplacementAtr, obAtr, obMaxBlocks);
                inBullOB = marketStructureAnalyzer.isPriceInBullishOB(currentPrice.doubleValue(), obs);
                inBearOB = marketStructureAnalyzer.isPriceInBearishOB(currentPrice.doubleValue(), obs);
                logger.info("🧱 Order Blocks: {} active | price in demand-OB={} supply-OB={}",
                        obs.size(), inBullOB, inBearOB);
            }

            // ── Pure SMC entry ──────────────────────────────────────────────────────
            // Entry = valid Order Block aligned with H1 structure, inside kill zone.
            // Structural SL + >=1:2 R:R is enforced in TradeManager (aborts if unreachable).
            boolean longTrigger = allowLong && inBullOB;
            boolean shortTrigger = allowShort && inBearOB;

            if (longTrigger) {
                if (tradeManager.hasOpenPosition(sym, userId, "LONG")) {
                    logger.info("SMC LONG skip: a LONG is already open for {}", sym);
                } else {
                    fireSmcEntry(sym, userId, currentPrice, "LONG", marketContext);
                }
            } else {
                saveRejection(sym, "LONG", "SMC", allowLong ? "NO_DEMAND_OB" : "HTF_STRUCTURE",
                        currentPrice, 0.0, 0.0, 0.0);
            }

            if (shortTrigger) {
                if (tradeManager.hasOpenPosition(sym, userId, "SHORT")) {
                    logger.info("SMC SHORT skip: a SHORT is already open for {}", sym);
                } else {
                    fireSmcEntry(sym, userId, currentPrice, "SHORT", marketContext);
                }
            } else {
                saveRejection(sym, "SHORT", "SMC", allowShort ? "NO_SUPPLY_OB" : "HTF_STRUCTURE",
                        currentPrice, 0.0, 0.0, 0.0);
            }

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
        } finally {
            rsiOversold   = savedRsiOversold;
            rsiOverbought = savedRsiOverbought;
        }
    }

    /**
     * Pure SMC entry: builds a minimal signal and delegates to TradeManager, which applies
     * the structural SL + >=1:2 R:R filter (and aborts the order if it is not achievable).
     * No BB/Stochastic/RSI/VWAP/EMA confirmation is used in this path.
     */
    private void fireSmcEntry(String sym, Long userId, BigDecimal currentPrice, String action, MarketContext ctx) {
        Signal signal = new Signal();
        signal.setSymbol(sym);
        signal.setAction(action);
        signal.setPrice(currentPrice);
        signal.setSetupType("SMC_" + action);
        signal.setUserId(userId);
        if (ctx != null) enrichSignalWithContext(signal, ctx);
        if (tradeManager.isReEntryEligible(sym, action)) {
            signal.setReEntry(true);
            signal.setPositionSizeFactor(0.5);
        }
        signalRepository.save(signal);
        logger.info("🎯 SMC {} entry for {} @ {} (structural SL + R:R enforced in TradeManager)",
                action, sym, currentPrice);
        if ("LONG".equals(action)) {
            tradeManager.executeLongEntry(signal);
        } else {
            tradeManager.executeShortEntry(signal);
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
                logger.info("📊 Suggested adjustments: RSI oversold={}, minMomentum={}",
                        String.format("%.1f", adjustments.suggestedRsiOversold), String.format("%.2f", adjustments.suggestedMinMomentum));
                // Aplicar ajustes conservadores (max 5% delta)
                double deltaRsi = Math.abs(adjustments.suggestedRsiOversold - rsiOversold);
                double deltaMomentum = Math.abs(adjustments.suggestedMinMomentum - minMomentum);
                if (deltaRsi < 5.0) {
                    rsiOversold = adjustments.suggestedRsiOversold;
                }
                if (deltaMomentum < 0.5) {
                    minMomentum = adjustments.suggestedMinMomentum;
                }
                logger.info("✅ Thresholds updated: rsiOversold={}, minMomentum={}", String.format("%.1f", rsiOversold), String.format("%.2f", minMomentum));
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

    // ============== Per-symbol config helpers ==============

    /**
     * Returns true if sym has no session restriction, or if the current UTC hour is inside
     * any of the configured windows. Symbols are comma-separated; a symbol maps to one or
     * more '|'-separated hour windows.
     *
     * Format (Kill Zones): "EURUSD:0-3|7-10|12-15,GBPUSD:0-3|7-10|12-15"
     *   0-3   = Asia (Tokyo) kill zone
     *   7-10  = London kill zone
     *   12-15 = New York kill zone
     * A window that wraps midnight (e.g. 23-2) is supported.
     */
    private boolean isSymbolInSession(String sym) {
        if (symbolSessionConfig == null || symbolSessionConfig.isBlank()) return true;
        for (String entry : symbolSessionConfig.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(sym)) {
                int utcH = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
                for (String window : parts[1].split("\\|")) {
                    String[] hours = window.trim().split("-");
                    if (hours.length == 2) {
                        try {
                            int start = Integer.parseInt(hours[0].trim());
                            int end   = Integer.parseInt(hours[1].trim());
                            boolean inWindow = (start <= end)
                                    ? (utcH >= start && utcH < end)          // normal window
                                    : (utcH >= start || utcH < end);         // wraps midnight
                            if (inWindow) return true;
                        } catch (NumberFormatException ignored) {}
                    }
                }
                return false; // symbol configured but current hour outside all its windows
            }
        }
        return true;
    }

    private double getSymbolRsiOversold(String sym) {
        return parseSymbolDoubleConfig(symbolRsiOversoldConfig, sym, rsiOversold);
    }

    private double getSymbolRsiOverbought(String sym) {
        return parseSymbolDoubleConfig(symbolRsiOverboughtConfig, sym, rsiOverbought);
    }

    private double parseSymbolDoubleConfig(String config, String sym, double defaultVal) {
        if (config == null || config.isBlank()) return defaultVal;
        for (String entry : config.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2 && parts[0].trim().equalsIgnoreCase(sym)) {
                try { return Double.parseDouble(parts[1].trim()); } catch (NumberFormatException ignored) {}
            }
        }
        return defaultVal;
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
