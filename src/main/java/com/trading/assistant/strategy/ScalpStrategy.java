package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.execution.TradeManager;
import com.trading.assistant.strategy.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
@ConditionalOnProperty(name = "trading.strategy.hunter.mode-enabled", havingValue = "true", matchIfMissing = false)
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

    @Autowired
    private com.trading.assistant.portfolio.repository.RejectedSignalRepository rejectedSignalRepository;

    @Value("${trading.strategy.hunter.mode-enabled:true}")
    private boolean hunterModeEnabled;

    private volatile boolean running = true;

    @Value("${trading.strategy.symbols:HYPEUSDT}")
    private String symbolsConfig;

    private List<String> getSymbols() {
        return java.util.Arrays.asList(symbolsConfig.split(","));
    }

    // Symbol-specific hunter config: SYMBOL:slPct:tpPct:rsiOversold:rsiOverbought:momentumThreshold:minVolumeRatio
    @Value("${trading.strategy.hunter.symbol-config:}")
    private String hunterSymbolConfig;

    private final java.util.Map<String, HunterSymbolConfig> hunterSymbolConfigMap = new java.util.concurrent.ConcurrentHashMap<>();

    private void initHunterSymbolConfig() {
        if (hunterSymbolConfig == null || hunterSymbolConfig.isBlank() || !hunterSymbolConfigMap.isEmpty()) return;
        for (String part : hunterSymbolConfig.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length >= 7) {
                try {
                    HunterSymbolConfig cfg = new HunterSymbolConfig();
                    cfg.slPct = Double.parseDouble(kv[1]);
                    cfg.tpPct = Double.parseDouble(kv[2]);
                    cfg.rsiOversold = Double.parseDouble(kv[3]);
                    cfg.rsiOverbought = Double.parseDouble(kv[4]);
                    cfg.momentumThreshold = Double.parseDouble(kv[5]);
                    cfg.minVolumeRatio = Double.parseDouble(kv[6]);
                    hunterSymbolConfigMap.put(kv[0].trim(), cfg);
                    logger.info("🎯 Hunter config loaded for {}: SL={}%, TP={}%, RSI({}/{}), Mo>={}, Vol>={}x",
                            kv[0], cfg.slPct, cfg.tpPct, cfg.rsiOversold, cfg.rsiOverbought, cfg.momentumThreshold, cfg.minVolumeRatio);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid hunter symbol config segment: {}", part);
                }
            }
        }
    }

    private HunterSymbolConfig getHunterConfig(String symbol) {
        initHunterSymbolConfig();
        return hunterSymbolConfigMap.getOrDefault(symbol, null);
    }

    private static class HunterSymbolConfig {
        double slPct;
        double tpPct;
        double rsiOversold;
        double rsiOverbought;
        double momentumThreshold;
        double minVolumeRatio;
    }

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

    @Value("${trading.strategy.hunter.killzone-enabled:true}")
    private boolean killzoneEnabled;

    @Value("${trading.strategy.hunter.killzone-london-start:7}")
    private int killzoneLondonStart;

    @Value("${trading.strategy.hunter.killzone-london-end:10}")
    private int killzoneLondonEnd;

    @Value("${trading.strategy.hunter.killzone-ny-start:12}")
    private int killzoneNyStart;

    @Value("${trading.strategy.hunter.killzone-ny-end:15}")
    private int killzoneNyEnd;

    @Value("${trading.strategy.hunter.m5-trend-filter:true}")
    private boolean m5TrendFilter;

    @Value("${trading.strategy.hunter.induction-enabled:true}")
    private boolean inductionEnabled;

    @Value("${trading.strategy.hunter.induction-lookback:3}")
    private int inductionLookback;

    @Value("${trading.strategy.hunter.stoch-divergence-enabled:true}")
    private boolean stochDivergenceEnabled;

    @Value("${trading.strategy.hunter.stoch-period:14}")
    private int stochPeriod;

    @Value("${trading.strategy.hunter.stoch-smooth-k:3}")
    private int stochSmoothK;

    @Value("${trading.strategy.hunter.stoch-smooth-d:3}")
    private int stochSmoothD;

    // Balance filter for hunter: reject inductions in micro-chop (calibrated for 1m)
    @Value("${trading.strategy.hunter.use-balance-filter:false}")
    private boolean useHunterBalanceFilter;

    @Value("${trading.strategy.hunter.balance-lookback-short:15}")
    private int hunterBalanceLookbackShort;

    @Value("${trading.strategy.hunter.balance-lookback-long:30}")
    private int hunterBalanceLookbackLong;

    @Value("${trading.strategy.hunter.balance-atr-compression-ratio:0.50}")
    private double hunterBalanceAtrCompressionRatio;

    // Absorption filter for hunter: require institutional footprint for mean-rev in balance
    @Value("${trading.strategy.hunter.use-absorption-filter:false}")
    private boolean useHunterAbsorptionFilter;

    @Value("${trading.strategy.hunter.absorption-volume-multiplier:1.5}")
    private double hunterAbsorptionVolumeMultiplier;

    @Value("${trading.strategy.hunter.absorption-range-multiplier:0.5}")
    private double hunterAbsorptionRangeMultiplier;

    /**
     * Execute scalp strategy every 15 seconds (4x per 1m candle).
     * Only runs if hunter mode is enabled and market conditions pass the gate.
     */
    @Scheduled(fixedRate = 15000)
    public void executeScalpStrategy() {
        if (!hunterModeEnabled) {
            logger.debug("Hunter mode disabled via config. Skipping scalp strategy.");
            return;
        }
        if (!running) {
            logger.debug("Hunter mode paused (toggle OFF). Skipping scalp strategy.");
            return;
        }

        // Killzone filter: only trade during high-liquidity sessions
        if (killzoneEnabled && !isInKillzone()) {
            // Log every ~2 min to confirm hunter is alive without spamming
            if (System.currentTimeMillis() % 120000 < 20000) {
                logger.info("🕒 Outside killzone (UTC {}). Hunter waiting.", ZonedDateTime.now(ZoneOffset.UTC).getHour());
            } else {
                logger.debug("🕒 Outside killzone. Skipping scalp.");
            }
            return;
        }

        for (String sym : getSymbols()) {
            executeScalpForSymbol(sym.trim());
        }
    }

    private void executeScalpForSymbol(String sym) {
        logger.debug("🎯 Executing {} 1m SCALPING strategy...", sym);

        // Symbol-specific config (fallback to global defaults)
        HunterSymbolConfig cfg = getHunterConfig(sym);
        double symRsiOversold = cfg != null ? cfg.rsiOversold : rsiOversold;
        double symRsiOverbought = cfg != null ? cfg.rsiOverbought : rsiOverbought;
        double symMomentumThreshold = cfg != null ? cfg.momentumThreshold : momentumThreshold;
        double symMinVolumeRatio = cfg != null ? cfg.minVolumeRatio : 0.5; // default if no config

        try {
            // Fetch 1m klines (need enough for RSI, VWAP, EMA, Stoch)
            int minKlines = Math.max(lookbackBars + 30, stochPeriod + stochSmoothK + stochSmoothD + 10);
            List<Kline> klines1m = binanceClient.getKlines(sym, "1m", minKlines);

            if (klines1m == null || klines1m.size() < lookbackBars + 10) {
                logger.warn("Insufficient 1m klines ({}). Skipping scalp.", klines1m == null ? 0 : klines1m.size());
                return;
            }

            // Calculate 1m indicators
            BigDecimal currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines1m);
            double rsi = indicatorCalculator.calculateRSIFromKlines(klines1m, rsiPeriod);
            double previousRsi = 50.0;
            if (klines1m.size() > 2) {
                List<Kline> prevKlines = klines1m.subList(0, klines1m.size() - 1);
                previousRsi = indicatorCalculator.calculateRSIFromKlines(prevKlines, rsiPeriod);
            }
            double momentum = indicatorCalculator.calculateMomentumFromKlines(klines1m);
            double sessionLow = indicatorCalculator.calculateSessionLowFromKlines(klines1m, lookbackBars);
            double sessionHigh = indicatorCalculator.calculateSessionHighFromKlines(klines1m, lookbackBars);
            boolean inBuyZone = indicatorCalculator.isInBuyZone(currentPrice.doubleValue(), sessionLow, sessionHigh, 30.0);
            boolean inSellZone = indicatorCalculator.isInSellZone(currentPrice.doubleValue(), sessionLow, sessionHigh, 30.0);

            int vwapFrom = Math.max(0, klines1m.size() - vwapPeriod);
            BigDecimal vwap = indicatorCalculator.calculateVWAP(klines1m.subList(vwapFrom, klines1m.size()));
            double ema = indicatorCalculator.calculateEMAFromKlines(klines1m, emaPeriod);

            double vwapDistancePct = 0.0;
            if (vwap.compareTo(BigDecimal.ZERO) > 0) {
                vwapDistancePct = Math.abs(currentPrice.subtract(vwap).doubleValue()) / vwap.doubleValue() * 100.0;
            }

            // Stochastic on 1m for divergence detection
            double[] stoch1m = indicatorCalculator.calculateStochastic(klines1m, stochPeriod, stochSmoothK, stochSmoothD);
            double stochKCurrent = stoch1m[0];
            double stochKPrev3 = 50.0;
            int minForStochPrev = stochPeriod + stochSmoothK + stochSmoothD + 3;
            if (stochDivergenceEnabled && klines1m.size() > minForStochPrev + 3) {
                double[] stochPrev = indicatorCalculator.calculateStochastic(
                        klines1m.subList(0, klines1m.size() - 3), stochPeriod, stochSmoothK, stochSmoothD);
                stochKPrev3 = stochPrev[0];
            }

            // M5 trend filter: fetch 5m klines and get EMA9
            double ema9_5m = 0.0;
            if (m5TrendFilter) {
                List<Kline> klines5m = binanceClient.getKlines(sym, "5m", 20);
                if (klines5m != null && klines5m.size() >= 9) {
                    ema9_5m = indicatorCalculator.calculateEMAFromKlines(klines5m, 9);
                }
            }
            boolean m5Bullish = ema9_5m > 0 && currentPrice.doubleValue() > ema9_5m;
            boolean m5Bearish = ema9_5m > 0 && currentPrice.doubleValue() < ema9_5m;

            logger.info("🎯 Scalp 1m: Price={}, RSI={} (prev={}), Mo={}%, BuyZone={}, SellZone={}, VWAP={}, EMA9_5m={}, M5={}, Stoch%K={}",
                    currentPrice, String.format("%.2f", rsi), String.format("%.2f", previousRsi),
                    String.format("%.3f", momentum), inBuyZone, inSellZone,
                    String.format("%.4f", vwap), ema9_5m > 0 ? String.format("%.4f", ema9_5m) : "N/A",
                    m5Bullish ? "BULL" : (m5Bearish ? "BEAR" : "N/A"), String.format("%.1f", stochKCurrent));

            Kline currentKline = klines1m.get(klines1m.size() - 1);

            // Balance filter for hunter: detect micro-consolidation/chop on 1m
            boolean marketInBalance = false;
            if (useHunterBalanceFilter) {
                marketInBalance = indicatorCalculator.isMarketInBalance(klines1m, hunterBalanceLookbackShort, hunterBalanceLookbackLong, hunterBalanceAtrCompressionRatio);
                logger.info("🎯 Scalp Balance (ATR{}/ATR{} < {}): {}", hunterBalanceLookbackShort, hunterBalanceLookbackLong, hunterBalanceAtrCompressionRatio, marketInBalance);
            }

            // Absorption filter for hunter: institutional footprint on 1m
            boolean absorptionDetected = false;
            if (useHunterAbsorptionFilter) {
                absorptionDetected = indicatorCalculator.detectAbsorption(currentKline, klines1m, hunterAbsorptionVolumeMultiplier, hunterAbsorptionRangeMultiplier);
                logger.info("🎯 Scalp Absorption (vol>{}×avg, range<{}×ATR): {}", hunterAbsorptionVolumeMultiplier, hunterAbsorptionRangeMultiplier, absorptionDetected);
            }

            // Calculate 1m ATR for volatility-based SL/TP
            double atr1m = indicatorCalculator.calculateATR(klines1m, 14);
            double atrPct = (atr1m / currentPrice.doubleValue()) * 100.0;

            // Evaluate scalp entries — ATR-based SL/TP calibrated to 1m volatility
            evaluateScalpLongEntry(sym, currentPrice, rsi, previousRsi, momentum, inBuyZone, vwap, vwapDistancePct, ema, klines1m, m5Bullish, stochKCurrent, stochKPrev3, marketInBalance, absorptionDetected, symRsiOversold, symRsiOverbought, symMomentumThreshold, symMinVolumeRatio, atr1m, atrPct);
            evaluateScalpShortEntry(sym, currentPrice, rsi, previousRsi, momentum, inSellZone, vwap, vwapDistancePct, ema, klines1m, m5Bearish, stochKCurrent, stochKPrev3, marketInBalance, absorptionDetected, symRsiOversold, symRsiOverbought, symMomentumThreshold, symMinVolumeRatio, atr1m, atrPct);

        } catch (Exception e) {
            logger.error("Error executing scalp strategy: {}", e.getMessage(), e);
        }
    }

    private void evaluateScalpLongEntry(String sym, BigDecimal currentPrice, double rsi, double previousRsi,
                                         double momentum, boolean inBuyZone, BigDecimal vwap,
                                         double vwapDistancePct, double ema, List<Kline> klines1m,
                                         boolean m5Bullish, double stochKCurrent, double stochKPrev3,
                                         boolean marketInBalance, boolean absorptionDetected,
                                         double symRsiOversold, double symRsiOverbought, double symMomentumThreshold, double symMinVolumeRatio,
                                         double atr1m, double atrPct) {
        // Per-direction gate check
        if (!marketConditionGate.canScalp(klines1m, "LONG")) {
            saveRejection(sym, "LONG", null, "MARKET_CONDITION_GATE", currentPrice, rsi, momentum, vwapDistancePct);
            return;
        }

        // M5 trend alignment: only LONG if price above EMA9(5m)
        if (m5TrendFilter && !m5Bullish) {
            logger.debug("No scalp LONG: M5 trend not bullish (price below EMA9_5m)");
            saveRejection(sym, "LONG", null, "M5_TREND_FILTER", currentPrice, rsi, momentum, vwapDistancePct);
            return;
        }

        // Condition 1: RSI oversold micro
        boolean rsiOversoldMicro = rsi <= symRsiOversold;
        // Condition 2: RSI reversing up (current > previous)
        boolean rsiReversingUp = rsi > previousRsi;
        // Condition 3: Momentum positive (current candle closing up)
        boolean momentumPositive = momentum >= symMomentumThreshold;
        // Condition 4: Price near VWAP (mean reversion target)
        boolean nearVwap = vwapDistancePct <= vwapProximityPct;
        // Condition 5: Price above EMA (micro trend aligned)
        boolean aboveEma = currentPrice.doubleValue() >= ema;
        // Condition 6: Volume spike
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, lookbackBars);

        // Induction (WWA): swept lows = stop hunt below support, then bullish reversal candle
        boolean inductionLong = inductionEnabled && hasLongInductionSignal(klines1m);

        // Stochastic bullish divergence: price lower low but stoch %K higher — hidden buying
        boolean bullishDiv = stochDivergenceEnabled && hasBullishDivergence(klines1m, stochKCurrent, stochKPrev3);

        boolean meanRevLong = rsiOversoldMicro && rsiReversingUp && momentumPositive;
        boolean vwapBounce = inBuyZone && nearVwap && momentumPositive && aboveEma;

        // Balance filter: in micro-chop, inductions (stop-hunt sweeps) are often false signals
        if (useHunterBalanceFilter && marketInBalance && inductionLong) {
            logger.info("❌ SCALP LONG Induction rejected: market in balance (micro-chop) — sweeps fail in chop");
            saveRejection(sym, "LONG", "SCALP_INDUCTION", "BALANCE_FILTER", currentPrice, rsi, momentum, vwapDistancePct);
            inductionLong = false;
        }

        // Absorption filter: in balance, mean-reversion needs institutional footprint at the level
        if (useHunterAbsorptionFilter && marketInBalance && (meanRevLong || vwapBounce) && !absorptionDetected) {
            logger.info("❌ SCALP LONG Mean-Rev/VWAP rejected: no absorption detected in balance (no institutional defense)");
            saveRejection(sym, "LONG", "SCALP_MEAN_REVERSION", "ABSORPTION_FILTER", currentPrice, rsi, momentum, vwapDistancePct);
            meanRevLong = false;
            vwapBounce = false;
        }

        // All scalp signals must align with M5 trend — NO counter-trend trades
        boolean longSignal = meanRevLong || vwapBounce || inductionLong || bullishDiv;
        if (longSignal && m5Bullish) {
            String entryType;
            if (inductionLong) entryType = "SCALP_INDUCTION";
            else if (bullishDiv) entryType = "SCALP_DIVERGENCE";
            else if (meanRevLong) entryType = "SCALP_MEAN_REVERSION";
            else entryType = "SCALP_VWAP_BOUNCE";
            logger.info("🟢 SCALP LONG signal: {} | RSI={}→{}, Mo={}%, nearVwap={}, vol={}x, induction={}, bullishDiv={}, ATR1m={}%",
                    entryType, String.format("%.2f", previousRsi), String.format("%.2f", rsi),
                    String.format("%.3f", momentum), nearVwap, String.format("%.2f", volRatio), inductionLong, bullishDiv, String.format("%.3f", atrPct));
            tradeManager.executeScalpLongEntry(sym, currentPrice, entryType, rsi, momentum, volRatio, atr1m, atrPct);
        } else {
            logger.debug("No scalp LONG (M5={}). MeanRev(RSI<{}:{}, RevUp:{}, Mo>{}:{}), VwapBounce(inBuy:{}, nearVwap:{}, aboveEma:{}), Induction:{}, BullishDiv:{}",
                    m5Bullish, symRsiOversold, rsiOversoldMicro, rsiReversingUp, symMomentumThreshold, momentumPositive,
                    inBuyZone, nearVwap, aboveEma, inductionLong, bullishDiv);
            saveRejection(sym, "LONG", null, "CONDITIONS_NOT_MET", currentPrice, rsi, momentum, vwapDistancePct);
        }
    }

    private void evaluateScalpShortEntry(String sym, BigDecimal currentPrice, double rsi, double previousRsi,
                                          double momentum, boolean inSellZone, BigDecimal vwap,
                                          double vwapDistancePct, double ema, List<Kline> klines1m,
                                          boolean m5Bearish, double stochKCurrent, double stochKPrev3,
                                          boolean marketInBalance, boolean absorptionDetected,
                                          double symRsiOversold, double symRsiOverbought, double symMomentumThreshold, double symMinVolumeRatio,
                                          double atr1m, double atrPct) {
        // Per-direction gate check
        if (!marketConditionGate.canScalp(klines1m, "SHORT")) {
            saveRejection(sym, "SHORT", null, "MARKET_CONDITION_GATE", currentPrice, rsi, momentum, vwapDistancePct);
            return;
        }

        // M5 trend alignment: only SHORT if price below EMA9(5m)
        if (m5TrendFilter && !m5Bearish) {
            logger.debug("No scalp SHORT: M5 trend not bearish (price above EMA9_5m)");
            saveRejection(sym, "SHORT", null, "M5_TREND_FILTER", currentPrice, rsi, momentum, vwapDistancePct);
            return;
        }

        // Condition 1: RSI overbought micro
        boolean rsiOverboughtMicro = rsi >= symRsiOverbought;
        // Condition 2: RSI reversing down
        boolean rsiReversingDown = rsi < previousRsi;
        // Condition 3: Momentum negative
        boolean momentumNegative = momentum <= -symMomentumThreshold;
        // Condition 4: Price near VWAP
        boolean nearVwap = vwapDistancePct <= vwapProximityPct;
        // Condition 5: Price below EMA (micro trend aligned)
        boolean belowEma = currentPrice.doubleValue() <= ema;
        // Condition 6: Volume spike
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, lookbackBars);

        // Induction (WWA): swept highs = stop hunt above resistance, then bearish reversal candle
        boolean inductionShort = inductionEnabled && hasShortInductionSignal(klines1m);

        // Stochastic bearish divergence: price higher high but stoch %K lower — hidden selling
        boolean bearishDiv = stochDivergenceEnabled && hasBearishDivergence(klines1m, stochKCurrent, stochKPrev3);

        boolean meanRevShort = rsiOverboughtMicro && rsiReversingDown && momentumNegative;
        boolean vwapRejection = inSellZone && nearVwap && momentumNegative && belowEma;

        // Balance filter: in micro-chop, inductions (stop-hunt sweeps) are often false signals
        if (useHunterBalanceFilter && marketInBalance && inductionShort) {
            logger.info("❌ SCALP SHORT Induction rejected: market in balance (micro-chop) — sweeps fail in chop");
            saveRejection(sym, "SHORT", "SCALP_INDUCTION", "BALANCE_FILTER", currentPrice, rsi, momentum, vwapDistancePct);
            inductionShort = false;
        }

        // Absorption filter: in balance, mean-reversion needs institutional footprint at the level
        if (useHunterAbsorptionFilter && marketInBalance && (meanRevShort || vwapRejection) && !absorptionDetected) {
            logger.info("❌ SCALP SHORT Mean-Rev/VWAP rejected: no absorption detected in balance (no institutional defense)");
            saveRejection(sym, "SHORT", "SCALP_MEAN_REVERSION", "ABSORPTION_FILTER", currentPrice, rsi, momentum, vwapDistancePct);
            meanRevShort = false;
            vwapRejection = false;
        }

        // All scalp signals must align with M5 trend — NO counter-trend trades
        boolean shortSignal = meanRevShort || vwapRejection || inductionShort || bearishDiv;
        if (shortSignal && m5Bearish) {
            String entryType;
            if (inductionShort) entryType = "SCALP_INDUCTION";
            else if (bearishDiv) entryType = "SCALP_DIVERGENCE";
            else if (meanRevShort) entryType = "SCALP_MEAN_REVERSION";
            else entryType = "SCALP_VWAP_REJECTION";
            logger.info("🔴 SCALP SHORT signal: {} | RSI={}→{}, Mo={}%, nearVwap={}, vol={}x, induction={}, bearishDiv={}, ATR1m={}%",
                    entryType, String.format("%.2f", previousRsi), String.format("%.2f", rsi),
                    String.format("%.3f", momentum), nearVwap, String.format("%.2f", volRatio), inductionShort, bearishDiv, String.format("%.3f", atrPct));
            tradeManager.executeScalpShortEntry(sym, currentPrice, entryType, rsi, momentum, volRatio, atr1m, atrPct);
        } else {
            logger.debug("No scalp SHORT (M5={}). MeanRev(RSI>{}:{}, RevDown:{}, Mo<-{}:{}), VwapReject(inSell:{}, nearVwap:{}, belowEma:{}), Induction:{}, BearishDiv:{}",
                    m5Bearish, symRsiOverbought, rsiOverboughtMicro, rsiReversingDown, symMomentumThreshold, momentumNegative,
                    inSellZone, nearVwap, belowEma, inductionShort, bearishDiv);
            saveRejection(sym, "SHORT", null, "CONDITIONS_NOT_MET", currentPrice, rsi, momentum, vwapDistancePct);
        }
    }

    // ============ HELPER METHODS (WWA methodology) ============

    /** Killzone: only trade during London (07-10 UTC) and NY (12-15 UTC) sessions */
    private boolean isInKillzone() {
        int hour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        boolean london = hour >= killzoneLondonStart && hour < killzoneLondonEnd;
        boolean ny     = hour >= killzoneNyStart     && hour < killzoneNyEnd;
        return london || ny;
    }

    /**
     * Long induction (WWA): last completed candle swept below recent lows (stop hunt)
     * but closed back above as a bullish candle — high-probability LONG entry.
     */
    private boolean hasLongInductionSignal(List<Kline> klines) {
        if (klines.size() < inductionLookback + 3) return false;
        int lastIdx = klines.size() - 2; // last completed candle (not forming)
        Kline last = klines.get(lastIdx);
        double lastLow   = last.getLow().doubleValue();
        double lastClose = last.getClose().doubleValue();
        double lastOpen  = last.getOpen().doubleValue();
        // Find lowest low in previous N candles
        double prevLowest = Double.MAX_VALUE;
        for (int i = lastIdx - inductionLookback; i < lastIdx; i++) {
            if (i >= 0) prevLowest = Math.min(prevLowest, klines.get(i).getLow().doubleValue());
        }
        // Induction: swept below previous lows AND candle closed bullish
        boolean sweptLows = lastLow < prevLowest;
        boolean bullishClose = lastClose > lastOpen;
        return sweptLows && bullishClose;
    }

    /**
     * Short induction (WWA): last completed candle swept above recent highs (stop hunt)
     * but closed back below as a bearish candle — high-probability SHORT entry.
     */
    private boolean hasShortInductionSignal(List<Kline> klines) {
        if (klines.size() < inductionLookback + 3) return false;
        int lastIdx = klines.size() - 2;
        Kline last = klines.get(lastIdx);
        double lastHigh  = last.getHigh().doubleValue();
        double lastClose = last.getClose().doubleValue();
        double lastOpen  = last.getOpen().doubleValue();
        double prevHighest = Double.MIN_VALUE;
        for (int i = lastIdx - inductionLookback; i < lastIdx; i++) {
            if (i >= 0) prevHighest = Math.max(prevHighest, klines.get(i).getHigh().doubleValue());
        }
        boolean sweptHighs = lastHigh > prevHighest;
        boolean bearishClose = lastClose < lastOpen;
        return sweptHighs && bearishClose;
    }

    /**
     * Bullish stochastic divergence: price making lower lows but %K making higher lows.
     * Signals hidden buying pressure — support LONG entry.
     */
    private boolean hasBullishDivergence(List<Kline> klines, double stochKCurrent, double stochKPrev3) {
        if (klines.size() < 8) return false;
        // Recent low (last 3 candles vs 3 candles ago window)
        double recentLow = Double.MAX_VALUE;
        for (int i = klines.size() - 4; i < klines.size() - 1; i++) {
            recentLow = Math.min(recentLow, klines.get(i).getLow().doubleValue());
        }
        double olderLow = Double.MAX_VALUE;
        for (int i = klines.size() - 7; i < klines.size() - 4; i++) {
            if (i >= 0) olderLow = Math.min(olderLow, klines.get(i).getLow().doubleValue());
        }
        // Divergence: price lower low + stoch higher %K
        return recentLow < olderLow && stochKCurrent > stochKPrev3 + 5.0;
    }

    /**
     * Bearish stochastic divergence: price making higher highs but %K making lower highs.
     * Signals hidden selling pressure — support SHORT entry.
     */
    private boolean hasBearishDivergence(List<Kline> klines, double stochKCurrent, double stochKPrev3) {
        if (klines.size() < 8) return false;
        double recentHigh = Double.MIN_VALUE;
        for (int i = klines.size() - 4; i < klines.size() - 1; i++) {
            recentHigh = Math.max(recentHigh, klines.get(i).getHigh().doubleValue());
        }
        double olderHigh = Double.MIN_VALUE;
        for (int i = klines.size() - 7; i < klines.size() - 4; i++) {
            if (i >= 0) olderHigh = Math.max(olderHigh, klines.get(i).getHigh().doubleValue());
        }
        // Divergence: price higher high + stoch lower %K
        return recentHigh > olderHigh && stochKCurrent < stochKPrev3 - 5.0;
    }

    // Phase 3.1: Rejection tracking helper
    private void saveRejection(String symbol, String action, String setupType, String reason,
                               BigDecimal price, double rsi, double momentum, double vwapDist) {
        try {
            if (rejectedSignalRepository != null) {
                rejectedSignalRepository.save(new com.trading.assistant.portfolio.model.RejectedSignal(
                        symbol, action, "SCALP", setupType, reason,
                        price, BigDecimal.valueOf(rsi), BigDecimal.valueOf(momentum), BigDecimal.valueOf(vwapDist)));
            }
        } catch (Exception e) {
            logger.debug("Failed to save scalp rejection: {}", e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (!hunterModeEnabled) {
            throw new IllegalStateException("Cannot start: hunter mode is disabled in configuration");
        }
        this.running = true;
        logger.info("Hunter/Scalp STARTED (toggle ON)");
    }

    public void stop() {
        this.running = false;
        logger.info("Hunter/Scalp STOPPED (toggle OFF)");
    }

    public boolean toggle() {
        if (!hunterModeEnabled) {
            throw new IllegalStateException("Cannot toggle: hunter mode is disabled in configuration");
        }
        this.running = !this.running;
        logger.info("Hunter/Scalp toggled: running={}", this.running);
        return this.running;
    }
}
