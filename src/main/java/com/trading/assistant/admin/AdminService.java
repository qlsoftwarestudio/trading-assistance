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
    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.timeframe:5m}")
    private String timeframe;

    @Value("${trading.strategy.enabled:true}")
    private boolean strategyEnabled;

    @Value("${trading.strategy.rsi-length:7}")
    private int rsiLength;

    @Value("${trading.strategy.rsi-oversold:40}")
    private double rsiOversold;

    @Value("${trading.strategy.rsi-overbought:60}")
    private double rsiOverbought;

    @Value("${trading.strategy.lookback-bars:12}")
    private int lookbackBars;

    @Value("${trading.strategy.killzone-threshold:30.0}")
    private double killzoneThreshold;

    @Value("${trading.strategy.min-momentum:0.05}")
    private double minMomentum;

    @Value("${trading.strategy.stop-loss-pct:0.8}")
    private double stopLossPct;

    @Value("${trading.strategy.take-profit-pct:1.5}")
    private double takeProfitPct;

    @Value("${trading.strategy.position-size-pct:10.0}")
    private double positionSizePct;

    @Value("${trading.strategy.leverage:5}")
    private int leverage;

    @Value("${trading.strategy.use-atr-stop:true}")
    private boolean useAtrStop;

    @Value("${trading.strategy.atr-period:10}")
    private int atrPeriod;

    @Value("${trading.strategy.atr-multiplier:1.5}")
    private double atrMultiplier;

    @Value("${trading.context.enabled:false}")
    private boolean contextEnabled;

    @Value("${trading.context.require-confluence:false}")
    private boolean requireConfluence;

    @Value("${trading.context.require-volume:false}")
    private boolean requireVolume;

    @Value("${trading.context.min-volume-ratio:1.0}")
    private double minVolumeRatio;

    @Value("${trading.performance.auto-adjust:false}")
    private boolean autoAdjust;

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

        config.put("contextEnabled", contextEnabled);
        config.put("requireConfluence", requireConfluence);
        config.put("requireVolume", requireVolume);
        config.put("minVolumeRatio", minVolumeRatio);

        config.put("autoAdjust", autoAdjust);

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
