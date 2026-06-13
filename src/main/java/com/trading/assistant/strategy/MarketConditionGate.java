package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.BookTicker;
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
    private BinanceClient binanceClient;

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
     * Evaluate if market conditions allow scalp entries.
     * @param klines1m 1m klines for volatility and volume
     * @return true if all conditions pass
     */
    public boolean canScalp(List<Kline> klines1m) {
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
        BookTicker bookTicker = binanceClient.getBookTicker();
        double spreadPct = bookTicker != null ? bookTicker.getSpreadPct() : 999.0;
        boolean spreadOk = spreadPct <= maxSpreadPct;

        // 3. Volume check: relative volume > min ratio
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, 20);
        boolean volumeOk = volRatio >= minVolumeRatio;

        // 4. Capacity check: ensure there's room for scalp trades
        long openCount = tradeRepository.countByStatus("OPEN");
        boolean capacityOk = openCount < (maxConcurrentTrades + hunterMaxConcurrent);

        boolean allOk = volatilityOk && spreadOk && volumeOk && capacityOk;

        if (allOk) {
            logger.info("🎯 MarketConditionGate: PASS. Vol={:.2f}% (min {}%), Spread={:.3f}% (max {}%), VolRatio={:.2f}x (min {}x), Open={}/{}+{}",
                    volatilityPct, minVolatilityPct,
                    spreadPct, maxSpreadPct,
                    volRatio, minVolumeRatio,
                    openCount, maxConcurrentTrades, hunterMaxConcurrent);
        } else {
            logger.debug("🚫 MarketConditionGate: FAIL. Vol={:.2f}% ({}), Spread={:.3f}% ({}), VolRatio={:.2f}x ({}), Open={}/{}+{}",
                    volatilityPct, volatilityOk ? "OK" : "LOW",
                    spreadPct, spreadOk ? "OK" : "WIDE",
                    volRatio, volumeOk ? "OK" : "LOW",
                    openCount, maxConcurrentTrades, hunterMaxConcurrent);
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
        BookTicker bookTicker = binanceClient.getBookTicker();
        double spreadPct = bookTicker != null ? bookTicker.getSpreadPct() : 999.0;
        double volRatio = indicatorCalculator.calculateRelativeVolume(klines1m, 20);

        logger.info("📊 Market conditions (1m): Vol={:.2f}% (min {}%), Spread={:.3f}% (max {}%), VolRatio={:.2f}x (min {}x)",
                volatilityPct, minVolatilityPct,
                spreadPct, maxSpreadPct,
                volRatio, minVolumeRatio);
    }
}
