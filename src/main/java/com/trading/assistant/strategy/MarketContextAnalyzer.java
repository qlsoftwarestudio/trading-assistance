package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.strategy.model.MarketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Analiza el contexto macro del mercado usando multi-timeframe,
 * volumen, soportes/resistencias y correlacion con BTC.
 */
@Component
public class MarketContextAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(MarketContextAnalyzer.class);

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.context.enabled:true}")
    private boolean contextEnabled;

    @Value("${trading.context.min-volume-ratio:1.0}")
    private double minVolumeRatio;

    private static final String BTC_SYMBOL = "BTCUSDT";
    private static final String[] TIMEFRAMES = {"1h", "4h", "1d"};

    /**
     * Build market context for the configured symbol.
     * Returns null if context analysis is disabled.
     */
    public MarketContext analyze() {
        if (!contextEnabled) {
            logger.debug("Market context analysis is disabled");
            return null;
        }

        try {
            MarketContext ctx = new MarketContext();
            ctx.setTimeframe("15m");

            // 1. Multi-timeframe trend detection
            List<Kline> klines1h = binanceClient.getKlines(symbol, "1h", 250);
            List<Kline> klines4h = binanceClient.getKlines(symbol, "4h", 200);
            List<Kline> klines1d = binanceClient.getKlines(symbol, "1d", 50);

            if (klines1h != null && klines1h.size() >= 200) {
                ctx.setTrend1h(indicatorCalculator.detectTrend(klines1h));
                ctx.setEma20_1h(BigDecimal.valueOf(indicatorCalculator.calculateEMAFromKlines(klines1h, 20)));
                ctx.setEma50_1h(BigDecimal.valueOf(indicatorCalculator.calculateEMAFromKlines(klines1h, 50)));
                ctx.setEma200_1h(BigDecimal.valueOf(indicatorCalculator.calculateEMAFromKlines(klines1h, 200)));
            } else {
                ctx.setTrend1h(MarketContext.TrendDirection.SIDEWAYS);
            }

            if (klines4h != null && klines4h.size() >= 200) {
                ctx.setTrend4h(indicatorCalculator.detectTrend(klines4h));
            } else if (klines4h != null && klines4h.size() >= 50) {
                ctx.setTrend4h(indicatorCalculator.detectTrend(klines4h));
            } else {
                ctx.setTrend4h(MarketContext.TrendDirection.SIDEWAYS);
            }

            if (klines1d != null && klines1d.size() >= 20) {
                // Use price vs EMA9 daily for faster trend detection (less lag than EMA20/50/200 cross)
                double ema9_1d = indicatorCalculator.calculateEMAFromKlines(klines1d, 9);
                double lastClose = klines1d.get(klines1d.size() - 1).getClose().doubleValue();
                double prevClose = klines1d.get(klines1d.size() - 2).getClose().doubleValue();
                if (lastClose < ema9_1d && prevClose < ema9_1d) {
                    ctx.setTrend1d(MarketContext.TrendDirection.DOWN);
                } else if (lastClose > ema9_1d && prevClose > ema9_1d) {
                    ctx.setTrend1d(MarketContext.TrendDirection.UP);
                } else {
                    ctx.setTrend1d(MarketContext.TrendDirection.SIDEWAYS);
                }
                logger.info("trend1d calc: lastClose={}, prevClose={}, EMA9_1d={}, result={}",
                        String.format("%.4f", lastClose), String.format("%.4f", prevClose),
                        String.format("%.4f", ema9_1d), ctx.getTrend1d());
            } else {
                ctx.setTrend1d(MarketContext.TrendDirection.SIDEWAYS);
            }

            // 2. Volume analysis (use 1h klines for finer granularity)
            if (klines1h != null && !klines1h.isEmpty()) {
                ctx.setRelativeVolume(indicatorCalculator.calculateRelativeVolume(klines1h, 24));
                ctx.setObvSlope(indicatorCalculator.calculateOBVSlope(klines1h, 24));
            }

            // 3. Support / Resistance (use 4h for meaningful levels)
            BigDecimal currentPrice = binanceClient.getCurrentPrice();
            if (klines4h != null && currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal support = indicatorCalculator.findNearestSupport(klines4h, currentPrice, 60);
                BigDecimal resistance = indicatorCalculator.findNearestResistance(klines4h, currentPrice, 60);
                ctx.setNearestSupport(support);
                ctx.setNearestResistance(resistance);
                ctx.setDistanceToSupportPct(indicatorCalculator.distanceToLevelPct(currentPrice, support));
                ctx.setDistanceToResistancePct(indicatorCalculator.distanceToLevelPct(currentPrice, resistance));
            }

            // 4. BTC Correlation
            analyzeBtcCorrelation(ctx, symbol, currentPrice);

            // 5. Confluence check
            int upCount = 0, downCount = 0;
            for (MarketContext.TrendDirection t : new MarketContext.TrendDirection[]{ctx.getTrend1h(), ctx.getTrend4h(), ctx.getTrend1d()}) {
                if (t == MarketContext.TrendDirection.UP) upCount++;
                else if (t == MarketContext.TrendDirection.DOWN) downCount++;
            }
            ctx.setConfluence(upCount >= 2 || downCount >= 2);

            logger.info("Market Context: {}", ctx);
            return ctx;

        } catch (Exception e) {
            logger.error("Error building market context: {}", e.getMessage(), e);
            return null;
        }
    }

    private void analyzeBtcCorrelation(MarketContext ctx, String symbol, BigDecimal currentPrice) {
        try {
            List<Kline> symbolKlines = binanceClient.getKlines(symbol, "1h", 100);
            List<Kline> btcKlines = binanceClient.getKlines(BTC_SYMBOL, "1h", 100);

            if (symbolKlines != null && btcKlines != null
                    && symbolKlines.size() == btcKlines.size()
                    && symbolKlines.size() >= 20) {

                List<BigDecimal> symbolCloses = symbolKlines.stream()
                        .map(Kline::getClose)
                        .collect(Collectors.toList());
                List<BigDecimal> btcCloses = btcKlines.stream()
                        .map(Kline::getClose)
                        .collect(Collectors.toList());

                double correlation = indicatorCalculator.calculateCorrelation(symbolCloses, btcCloses);
                ctx.setBtcCorrelation(correlation);

                // BTC trend on daily
                List<Kline> btcDaily = binanceClient.getKlines(BTC_SYMBOL, "1d", 50);
                if (btcDaily != null && btcDaily.size() >= 50) {
                    ctx.setBtcTrend1d(indicatorCalculator.detectTrend(btcDaily));
                } else {
                    ctx.setBtcTrend1d(MarketContext.TrendDirection.SIDEWAYS);
                }
            } else {
                ctx.setBtcCorrelation(0.0);
                ctx.setBtcTrend1d(MarketContext.TrendDirection.SIDEWAYS);
            }
        } catch (Exception e) {
            logger.warn("Could not compute BTC correlation: {}", e.getMessage());
            ctx.setBtcCorrelation(0.0);
            ctx.setBtcTrend1d(MarketContext.TrendDirection.SIDEWAYS);
        }
    }

    /**
     * Check if volume is sufficient for a valid signal
     */
    public boolean hasEnoughVolume(MarketContext ctx) {
        if (ctx == null) return true; // passthrough if disabled
        return ctx.getRelativeVolume() >= minVolumeRatio;
    }
}
