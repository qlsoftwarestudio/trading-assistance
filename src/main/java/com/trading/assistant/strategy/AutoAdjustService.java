package com.trading.assistant.strategy;

import com.trading.assistant.portfolio.repository.TradeJournalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AutoAdjustService {

    private static final Logger logger = LoggerFactory.getLogger(AutoAdjustService.class);

    @Autowired
    private TradeJournalRepository tradeJournalRepository;

    @Value("${trading.strategy.auto-adjust-enabled:true}")
    private boolean autoAdjustEnabled;

    @Value("${trading.strategy.auto-adjust-min-trades:20}")
    private int minTradesForAdjust;

    @Value("${trading.strategy.auto-adjust-win-rate-threshold:0.30}")
    private double winRateThreshold;

    @Value("${trading.strategy.auto-adjust-lookback-days:30}")
    private int lookbackDays;

    // Setup key format: "ACTION:SETUP_TYPE" e.g. "LONG:Mean-Reversion"
    private final Map<String, Boolean> setupEnabled = new ConcurrentHashMap<>();

    /**
     * Check if a setup is currently enabled for trading.
     */
    public boolean isSetupEnabled(String action, String setupType) {
        if (!autoAdjustEnabled) return true;
        String key = action + ":" + setupType;
        return setupEnabled.getOrDefault(key, true);
    }

    /**
     * Periodically evaluate setup performance and disable underperforming ones.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedRate = 600000)
    public void evaluateSetupPerformance() {
        if (!autoAdjustEnabled) return;

        try {
            LocalDateTime since = LocalDateTime.now().minusDays(lookbackDays);
            String[] actions = {"LONG", "SHORT"};
            String[] setups = {"Mean-Reversion", "Breakout", "Trend-Dip"};

            for (String action : actions) {
                for (String setup : setups) {
                    String key = action + ":" + setup;
                    Long total = tradeJournalRepository.countTotalBySetupTypeAndAction(setup, action, since);
                    if (total == null || total < minTradesForAdjust) {
                        continue; // Not enough data yet
                    }

                    Long wins = tradeJournalRepository.countWinsBySetupTypeAndAction(setup, action, since);
                    BigDecimal avgPnl = tradeJournalRepository.avgPnlBySetupTypeAndAction(setup, action, since);

                    double winRate = wins.doubleValue() / total.doubleValue();
                    boolean currentlyEnabled = setupEnabled.getOrDefault(key, true);

                    if (winRate < winRateThreshold && currentlyEnabled) {
                        setupEnabled.put(key, false);
                        logger.warn("🚫 Auto-adjust: {} {} disabled. Win rate: {:.1f}% ({} wins / {} trades), Avg P&L: ${}",
                                action, setup, winRate * 100, wins, total,
                                avgPnl != null ? avgPnl.setScale(2, RoundingMode.HALF_UP) : "N/A");
                    } else if (winRate >= winRateThreshold && !currentlyEnabled) {
                        setupEnabled.put(key, true);
                        logger.info("✅ Auto-adjust: {} {} re-enabled. Win rate: {:.1f}% ({} wins / {} trades), Avg P&L: ${}",
                                action, setup, winRate * 100, wins, total,
                                avgPnl != null ? avgPnl.setScale(2, RoundingMode.HALF_UP) : "N/A");
                    } else {
                        logger.debug("Auto-adjust: {} {} status={} (WR: {:.1f}%, {} trades)",
                                action, setup, currentlyEnabled ? "ON" : "OFF", winRate * 100, total);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in auto-adjust evaluation: {}", e.getMessage(), e);
        }
    }

    /**
     * Get current status of all setups for dashboard display.
     */
    public Map<String, Object> getSetupStatus() {
        Map<String, Object> status = new ConcurrentHashMap<>();
        String[] actions = {"LONG", "SHORT"};
        String[] setups = {"Mean-Reversion", "Breakout", "Trend-Dip"};

        for (String action : actions) {
            for (String setup : setups) {
                String key = action + ":" + setup;
                boolean enabled = setupEnabled.getOrDefault(key, true);
                status.put(key, enabled ? "ENABLED" : "DISABLED");
            }
        }
        status.put("autoAdjustEnabled", autoAdjustEnabled);
        status.put("minTradesForAdjust", minTradesForAdjust);
        status.put("winRateThreshold", winRateThreshold);
        return status;
    }
}
