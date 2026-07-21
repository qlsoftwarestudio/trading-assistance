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
    @Value("${trading.strategy.symbols:SOLUSDT}")
    private String symbols;

    @Value("${trading.strategy.symbol:SOLUSDT}")
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

    @Value("${trading.strategy.symbol-risk-config:}")
    private String symbolRiskConfig;

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

    @Value("${trading.strategy.use-htf-structure-filter:false}")
    private boolean useHtfStructureFilter;

    @Value("${trading.strategy.htf-timeframe:1h}")
    private String htfTimeframe;

    @Value("${trading.strategy.htf-pivot-strength:3}")
    private int htfPivotStrength;

    @Value("${trading.strategy.use-order-block-filter:false}")
    private boolean useOrderBlockFilter;

    @Value("${trading.strategy.ob-pivot-strength:2}")
    private int obPivotStrength;

    @Value("${trading.strategy.ob-displacement-atr:1.5}")
    private double obDisplacementAtr;

    @Value("${trading.strategy.ob-max-blocks:5}")
    private int obMaxBlocks;

    @Value("${trading.strategy.use-structural-sl:false}")
    private boolean useStructuralSl;

    @Value("${trading.strategy.rr-min-ratio:2.0}")
    private double rrMinRatio;

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

    @Value("${trading.strategy.auto-adjust-enabled:true}")
    private boolean autoAdjustEnabled;

    @Value("${trading.strategy.auto-adjust-min-trades:20}")
    private int autoAdjustMinTrades;

    @Value("${trading.strategy.auto-adjust-win-rate-threshold:0.30}")
    private double autoAdjustWinRateThreshold;

    @Value("${trading.risk.max-daily-loss-pct:5.0}")
    private double maxDailyLossPct;

    @Value("${telegram.bot.enabled:false}")
    private boolean telegramEnabled;

    @Value("${exchange.active:binance}")
    private String activeExchange;

    @Value("${trading.strategy.symbol-session-utc:}")
    private String symbolSessionUtc;

    @Value("${trading.strategy.symbol-rsi-oversold:}")
    private String symbolRsiOversold;

    @Value("${trading.strategy.symbol-rsi-overbought:}")
    private String symbolRsiOverbought;

    @Value("${trading.strategy.swing-trailing-stop-pct:0.35}")
    private double swingTrailingStopPct;

    @Value("${trading.strategy.swing-trailing-activation-pct:0.8}")
    private double swingTrailingActivationPct;

    @Value("${trading.strategy.partial-tp-enabled:true}")
    private boolean partialTpEnabled;

    @Value("${trading.strategy.re-entry-enabled:true}")
    private boolean reEntryEnabled;

    @Value("${trading.strategy.re-entry-window-minutes:15}")
    private int reEntryWindowMinutes;

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
        config.put("symbolRiskConfig", symbolRiskConfig);
        config.put("positionSizePct", positionSizePct);
        config.put("leverage", leverage);

        config.put("useAtrStop", useAtrStop);
        config.put("atrPeriod", atrPeriod);
        config.put("atrMultiplier", atrMultiplier);
        config.put("maxConcurrentTrades", maxConcurrentTrades);
        config.put("maxHoldMinutes", maxHoldMinutes);
        config.put("trailingStopPct", trailingStopPct);
        config.put("slCooldownMinutes", slCooldownMinutes);
        config.put("useHtfStructureFilter", useHtfStructureFilter);
        config.put("htfTimeframe", htfTimeframe);
        config.put("htfPivotStrength", htfPivotStrength);
        config.put("useOrderBlockFilter", useOrderBlockFilter);
        config.put("obPivotStrength", obPivotStrength);
        config.put("obDisplacementAtr", obDisplacementAtr);
        config.put("obMaxBlocks", obMaxBlocks);
        config.put("useStructuralSl", useStructuralSl);
        config.put("rrMinRatio", rrMinRatio);

        config.put("contextEnabled", contextEnabled);
        config.put("requireConfluence", requireConfluence);
        config.put("requireVolume", requireVolume);
        config.put("minVolumeRatio", minVolumeRatio);

        config.put("autoAdjust", autoAdjust);

        config.put("symbols", symbols);
        config.put("trailingActivationPct", trailingActivationPct);
        config.put("breakevenActivationPct", breakevenActivationPct);
        config.put("autoAdjustEnabled", autoAdjustEnabled);
        config.put("autoAdjustMinTrades", autoAdjustMinTrades);
        config.put("autoAdjustWinRateThreshold", autoAdjustWinRateThreshold);
        config.put("maxDailyLossPct", maxDailyLossPct);

        config.put("telegramEnabled", telegramEnabled);
        config.put("activeExchange", activeExchange);
        config.put("symbolSessionUtc", symbolSessionUtc);
        config.put("symbolRsiOversold", symbolRsiOversold);
        config.put("symbolRsiOverbought", symbolRsiOverbought);
        config.put("swingTrailingStopPct", swingTrailingStopPct);
        config.put("swingTrailingActivationPct", swingTrailingActivationPct);

        config.put("partialTpEnabled", partialTpEnabled);
        config.put("reEntryEnabled", reEntryEnabled);
        config.put("reEntryWindowMinutes", reEntryWindowMinutes);

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
        health.put("activeExchange", activeExchange);

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
