package com.trading.assistant.admin;

import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.repository.SignalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AdminService {

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private SignalRepository signalRepository;

    // Strategy config values (injected from application.yml / env vars)
    @Value("${trading.strategy.symbols:HYPEUSDT}")
    private String symbols;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.timeframe:5m}")
    private String timeframe;

    @Value("${trading.strategy.enabled:true}")
    private boolean strategyEnabled;

    @Value("${trading.strategy.rsi-length:7}")
    private int rsiLength;

    @Value("${trading.strategy.rsi-oversold:30}")
    private double rsiOversold;

    @Value("${trading.strategy.rsi-overbought:70}")
    private double rsiOverbought;

    @Value("${trading.strategy.lookback-bars:12}")
    private int lookbackBars;

    @Value("${trading.strategy.killzone-threshold:30.0}")
    private double killzoneThreshold;

    @Value("${trading.strategy.min-momentum:0.05}")
    private double minMomentum;

    @Value("${trading.strategy.stop-loss-pct:0.6}")
    private double stopLossPct;

    @Value("${trading.strategy.take-profit-pct:1.2}")
    private double takeProfitPct;

    @Value("${trading.strategy.position-size-pct:10.0}")
    private double positionSizePct;

    @Value("${trading.strategy.leverage:5}")
    private int leverage;

    @Value("${trading.strategy.use-atr-stop:false}")
    private boolean useAtrStop;

    @Value("${trading.strategy.atr-period:10}")
    private int atrPeriod;

    @Value("${trading.strategy.atr-multiplier:1.5}")
    private double atrMultiplier;

    @Value("${trading.strategy.max-concurrent-trades:2}")
    private int maxConcurrentTrades;

    @Value("${trading.strategy.max-hold-minutes:25}")
    private int maxHoldMinutes;

    @Value("${trading.strategy.trailing-stop-pct:0.6}")
    private double trailingStopPct;

    @Value("${trading.strategy.sl-cooldown-minutes:10}")
    private int slCooldownMinutes;

    @Value("${trading.strategy.require-rsi-reversal:true}")
    private boolean requireRsiReversal;

    @Value("${trading.strategy.use-vwap-filter:true}")
    private boolean useVwapFilter;

    @Value("${trading.strategy.vwap-band-pct:0.5}")
    private double vwapBandPct;

    @Value("${trading.strategy.use-ema-filter:true}")
    private boolean useEmaFilter;

    @Value("${trading.strategy.ema-period:9}")
    private int emaPeriod;

    @Value("${trading.context.enabled:true}")
    private boolean contextEnabled;

    @Value("${trading.context.require-confluence:false}")
    private boolean requireConfluence;

    @Value("${trading.context.require-volume:false}")
    private boolean requireVolume;

    @Value("${trading.context.min-volume-ratio:1.0}")
    private double minVolumeRatio;

    @Value("${trading.performance.auto-adjust:false}")
    private boolean autoAdjust;

    @Value("${trading.strategy.trailing-activation-pct:0.4}")
    private double trailingActivationPct;

    @Value("${trading.strategy.breakeven-activation-pct:0.25}")
    private double breakevenActivationPct;

    @Value("${trading.strategy.use-regression-filter:true}")
    private boolean useRegressionFilter;

    @Value("${trading.strategy.regression-lookback:50}")
    private int regressionLookback;

    @Value("${trading.strategy.use-delta-volume-filter:true}")
    private boolean useDeltaVolumeFilter;

    @Value("${trading.strategy.delta-volume-threshold:0.20}")
    private double deltaVolumeThreshold;

    @Value("${trading.strategy.use-stoch-bb-filter:false}")
    private boolean useStochBbFilter;

    @Value("${trading.strategy.stoch-period:14}")
    private int stochPeriod;

    @Value("${trading.strategy.stoch-oversold:20}")
    private double stochOversold;

    @Value("${trading.strategy.stoch-overbought:80}")
    private double stochOverbought;

    @Value("${trading.strategy.bb-period:20}")
    private int bbPeriod;

    @Value("${trading.strategy.bb-std-dev:2.0}")
    private double bbStdDev;

    @Value("${trading.strategy.bb-proximity-pct:1.0}")
    private double bbProximityPct;

    @Value("${trading.strategy.use-bb-based-sl:false}")
    private boolean useBbBasedSl;

    @Value("${trading.strategy.auto-adjust-enabled:true}")
    private boolean autoAdjustEnabled;

    @Value("${trading.strategy.auto-adjust-min-trades:20}")
    private int autoAdjustMinTrades;

    @Value("${trading.strategy.auto-adjust-win-rate-threshold:0.30}")
    private double autoAdjustWinRateThreshold;

    @Value("${trading.risk.max-daily-loss-pct:5.0}")
    private double maxDailyLossPct;

    @Value("${trading.strategy.rsi-overbought-uptrend:85}")
    private double rsiOverboughtUptrend;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${binance.testnet:true}")
    private boolean binanceTestnet;

    /**
     * Returns all current strategy and system configuration values.
     * Used by the frontend admin panel config display.
     */
    public Map<String, Object> getStrategyConfig() {
        Map<String, Object> config = new HashMap<>();

        config.put("symbol", symbol);
        config.put("timeframe", timeframe);
        config.put("enabled", strategyEnabled);

        config.put("rsiLength", rsiLength);
        config.put("rsiOversold", rsiOversold);
        config.put("rsiOverbought", rsiOverbought);
        config.put("lookbackBars", lookbackBars);
        config.put("killzoneThreshold", killzoneThreshold);
        config.put("minMomentum", minMomentum);

        config.put("stopLossPct", stopLossPct);
        config.put("takeProfitPct", takeProfitPct);
        config.put("positionSizePct", positionSizePct);
        config.put("leverage", leverage);

        config.put("useAtrStop", useAtrStop);
        config.put("atrPeriod", atrPeriod);
        config.put("atrMultiplier", atrMultiplier);
        config.put("maxConcurrentTrades", maxConcurrentTrades);
        config.put("maxHoldMinutes", maxHoldMinutes);
        config.put("trailingStopPct", trailingStopPct);
        config.put("slCooldownMinutes", slCooldownMinutes);
        config.put("requireRsiReversal", requireRsiReversal);
        config.put("useVwapFilter", useVwapFilter);
        config.put("vwapBandPct", vwapBandPct);
        config.put("useEmaFilter", useEmaFilter);
        config.put("emaPeriod", emaPeriod);

        config.put("contextEnabled", contextEnabled);
        config.put("requireConfluence", requireConfluence);
        config.put("requireVolume", requireVolume);
        config.put("minVolumeRatio", minVolumeRatio);

        config.put("autoAdjust", autoAdjust);

        config.put("symbols", symbols);
        config.put("trailingActivationPct", trailingActivationPct);
        config.put("breakevenActivationPct", breakevenActivationPct);
        config.put("useRegressionFilter", useRegressionFilter);
        config.put("regressionLookback", regressionLookback);
        config.put("useDeltaVolumeFilter", useDeltaVolumeFilter);
        config.put("deltaVolumeThreshold", deltaVolumeThreshold);
        config.put("useStochBbFilter", useStochBbFilter);
        config.put("stochPeriod", stochPeriod);
        config.put("stochOversold", stochOversold);
        config.put("stochOverbought", stochOverbought);
        config.put("bbPeriod", bbPeriod);
        config.put("bbStdDev", bbStdDev);
        config.put("bbProximityPct", bbProximityPct);
        config.put("useBbBasedSl", useBbBasedSl);
        config.put("autoAdjustEnabled", autoAdjustEnabled);
        config.put("autoAdjustMinTrades", autoAdjustMinTrades);
        config.put("autoAdjustWinRateThreshold", autoAdjustWinRateThreshold);
        config.put("maxDailyLossPct", maxDailyLossPct);
        config.put("rsiOverboughtUptrend", rsiOverboughtUptrend);

        config.put("telegramEnabled", telegramEnabled);
        config.put("binanceTestnet", binanceTestnet);

        return config;
    }

    /**
     * Returns a health snapshot of the bot: open trades, last signal timestamp, uptime.
     * Used by the frontend admin panel health cards.
     */
    public Map<String, Object> getBotHealth() {
        Map<String, Object> health = new HashMap<>();

        health.put("strategyEnabled", strategyEnabled);
        health.put("symbol", symbol);
        health.put("timeframe", timeframe);
        health.put("telegramEnabled", telegramEnabled);
        health.put("binanceTestnet", binanceTestnet);

        long openTrades = Optional.ofNullable(tradeRepository.countByStatus("OPEN")).orElse(0L);
        long totalTrades = tradeRepository.count();
        health.put("openTrades", openTrades);
        health.put("totalTrades", totalTrades);

        signalRepository.findTop1ByOrderByGeneratedAtDesc()
                .ifPresent(s -> health.put("lastSignalAt", s.getGeneratedAt() != null ? s.getGeneratedAt().toString() : null));

        health.put("uptime", formatUptime(ManagementFactory.getRuntimeMXBean().getUptime()));

        return health;
    }

    private String formatUptime(long uptimeMs) {
        long totalSeconds = uptimeMs / 1000;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (days > 0) return String.format("%dd %dh %dm", days, hours, minutes);
        if (hours > 0) return String.format("%dh %dm", hours, minutes);
        return String.format("%dm", minutes);
    }
}
