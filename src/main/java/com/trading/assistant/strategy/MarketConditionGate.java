package com.trading.assistant.strategy;

import com.trading.assistant.binance.ExchangeClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.portfolio.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gatekeeper for the scalping (hunter) strategy.
 * Only allows scalp entries when market conditions are optimal:
 * - High volatility (ATR > threshold)
 * - Tight spread (< threshold)
 * - Above-average volume (> threshold)
 * - No open swing trades blocking capacity
 */
@Component
public class MarketConditionGate {

    private static final Logger logger = LoggerFactory.getLogger(MarketConditionGate.class);

    @Autowired
    private ExchangeClient exchangeClient;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Autowired
    private TradeRepository tradeRepository;

    @Value("${trading.strategy.hunter.min-volatility-pct:1.0}")
    private double minVolatilityPct;

    @Value("${trading.strategy.hunter.max-spread-pct:0.05}")
    private double maxSpreadPct;

    @Value("${trading.strategy.hunter.min-volume-ratio:1.5}")
    private double minVolumeRatio;

    @Value("${trading.strategy.hunter.max-concurrent:1}")
    private int hunterMaxConcurrent;

    @Value("${trading.strategy.max-concurrent-trades:2}")
    private int maxConcurrentTrades;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    /**
     * Evaluate if market conditions allow scalp entries (any direction).
     * @param klines1m 1m klines for volatility and volume
     * @return true if all conditions pass
     */
    public boolean canScalp(List<Kline> klines1m) {
        return canScalp(klines1m, null);
    }

    /**
     * Evaluate if market conditions allow scalp entries for a specific direction.
     * Allows 1 LONG scalp + 1 SHORT scalp simultaneously (2 total hunters).
     * @param klines1m 1m klines for volatility and volume
     * @param action "LONG" or "SHORT" (null for generic check)
     * @return true if all conditions pass
     */
    public boolean canScalp(List<Kline> klines1m, String action) {
        if (klines1m == null || klines1m.size() < 20) {
            logger.debug("MarketConditionGate: insufficient 1m klines ({} < 20)", klines1m == null ? 0 : klines1m.size());
            return false;
        }

        double currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines1m).doubleValue();
        if (currentPrice <= 0) {
            logger.debug("MarketConditionGate: invalid current price");
            return false;
        }

        // 1. Volatility check: ATR(14) on 1m > min % of price
        double atr1m = indicatorCalculator.calculateATR(klines1m, 14);
        double volatilityPct = (atr1m / currentPrice) * 100.0;
        boolean volatilityOk = volatilityPct >= minVolatilityPct;

        // 2. Spread check: best bid/ask < max %
        double spreadPct = exchangeClient.getSpreadPct(symbol);
        boolean spreadOk = spreadPct <= maxSpreadPct;

        // 3. Volume check: relative volume > min ratio
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, 20);
        boolean volumeOk = volRatio >= minVolumeRatio;

        // 4. Capacity check: allow 1 LONG scalp + 1 SHORT scalp simultaneously
        long scalpCountSameDirection = 0;
        long totalScalpCount = 0;
        if (action != null) {
            var openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
            for (var trade : openTrades) {
                boolean isScalp = trade.getSetupType() != null && trade.getSetupType().startsWith("SCALP_");
                if (isScalp) {
                    totalScalpCount++;
                    if (action.equals(trade.getAction())) {
                        scalpCountSameDirection++;
                    }
                }
            }
        }
        // Max 1 scalp per direction, max hunterMaxConcurrent total
        boolean capacityOk = (action == null)
                ? tradeRepository.countByStatus("OPEN") < (maxConcurrentTrades + hunterMaxConcurrent)
                : (scalpCountSameDirection == 0 && totalScalpCount < hunterMaxConcurrent);

        boolean allOk = volatilityOk && spreadOk && volumeOk && capacityOk;

        if (allOk) {
            logger.info("🎯 MarketConditionGate: PASS for {}. Vol={}% (min {}%), Spread={}% (max {}%), VolRatio={}x (min {}x), Scalps={}/{}",
                    action != null ? action : "ANY",
                    String.format("%.2f", volatilityPct), minVolatilityPct,
                    String.format("%.3f", spreadPct), maxSpreadPct,
                    String.format("%.2f", volRatio), minVolumeRatio,
                    totalScalpCount, hunterMaxConcurrent);
        } else {
            logger.debug("🚫 MarketConditionGate: FAIL for {}. Vol={}% ({}), Spread={}% ({}), VolRatio={}x ({}), Scalps={}/{} sameDir={}",
                    action != null ? action : "ANY",
                    String.format("%.2f", volatilityPct), volatilityOk ? "OK" : "LOW",
                    String.format("%.3f", spreadPct), spreadOk ? "OK" : "WIDE",
                    String.format("%.2f", volRatio), volumeOk ? "OK" : "LOW",
                    totalScalpCount, hunterMaxConcurrent, scalpCountSameDirection);
        }

        return allOk;
    }

    /**
     * Log gate metrics without blocking.
     * Used to monitor market conditions even when hunter mode is disabled.
     */
    public void logMarketConditions(List<Kline> klines1m) {
        if (klines1m == null || klines1m.size() < 20) return;
        double currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines1m).doubleValue();
        if (currentPrice <= 0) return;

        double atr1m = indicatorCalculator.calculateATR(klines1m, 14);
        double volatilityPct = (atr1m / currentPrice) * 100.0;
        double spreadPct = exchangeClient.getSpreadPct(symbol);
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, 20);

        logger.info("📊 Market conditions (1m): Vol={}% (min {}%), Spread={}% (max {}%), VolRatio={}x (min {}x)",
                String.format("%.2f", volatilityPct), minVolatilityPct,
                String.format("%.3f", spreadPct), maxSpreadPct,
                String.format("%.2f", volRatio), minVolumeRatio);
    }
}
