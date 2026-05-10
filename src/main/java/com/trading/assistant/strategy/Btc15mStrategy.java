package com.trading.assistant.strategy;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.execution.TradeManager;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class Btc15mStrategy {

    private static final Logger logger = LoggerFactory.getLogger(Btc15mStrategy.class);

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Autowired
    private TradeManager tradeManager;

    @Autowired
    private SignalRepository signalRepository;

    @Value("${trading.strategy.enabled:true}")
    private boolean strategyEnabled;

    @Value("${trading.strategy.symbol:BTCUSDT}")
    private String symbol;

    @Value("${trading.strategy.rsi-length:5}")
    private int rsiLength;

    @Value("${trading.strategy.rsi-oversold:30}")
    private double rsiOversold;

    @Value("${trading.strategy.lookback-bars:12}")
    private int lookbackBars;

    @Value("${trading.strategy.killzone-threshold:1.0}")
    private double killzoneThreshold;

    @Value("${trading.strategy.min-momentum:0.8}")
    private double minMomentum;

    @Value("${trading.strategy.timeframe:15m}")
    private String timeframe;

    /**
     * Execute strategy every 15 minutes
     */
    @Scheduled(fixedRate = 900000) // 15 minutes = 900,000 ms
    public void executeStrategy() {
        if (!strategyEnabled) {
            logger.info("Strategy is disabled. Skipping execution.");
            return;
        }

        logger.info("Executing BTCUSD 15m SOLO LONG strategy...");

        try {
            // Get real kline data from Binance API
            List<Kline> klines = binanceClient.getKlines(timeframe, 50);
            
            if (klines == null || klines.isEmpty()) {
                logger.error("No kline data available. Skipping strategy execution.");
                return;
            }
            
            // Get current price from latest kline
            BigDecimal currentPrice = indicatorCalculator.getCurrentPriceFromKlines(klines);
            
            // Check if we already have an open position
            if (tradeManager.hasOpenPosition()) {
                logger.info("Open position exists. Skipping new signal.");
                return;
            }

            // Calculate indicators from real kline data
            double rsi = indicatorCalculator.calculateRSIFromKlines(klines);
            double sessionLow = indicatorCalculator.calculateSessionLowFromKlines(klines, lookbackBars);
            double momentum = indicatorCalculator.calculateMomentumFromKlines(klines);
            boolean inBuyZone = indicatorCalculator.isInBuyZone(
                    currentPrice.doubleValue(), sessionLow, killzoneThreshold);

            logger.info("Indicators (from {} klines) - RSI: {}, Session Low: {}, Momentum: {}%, In Buy Zone: {}",
                    klines.size(),
                    String.format("%.2f", rsi),
                    String.format("%.2f", sessionLow),
                    String.format("%.2f", momentum),
                    inBuyZone);

            // Evaluate entry conditions for SOLO LONG
            boolean rsiOversoldCondition = rsi < rsiOversold;
            boolean buyZoneCondition = inBuyZone;
            boolean momentumCondition = momentum > minMomentum;

            if (rsiOversoldCondition && buyZoneCondition && momentumCondition) {
                logger.info("🟢 LONG SIGNAL DETECTED! RSI: {}, Buy Zone: {}, Momentum: {}",
                        rsi, inBuyZone, momentum);

                // Generate signal
                Signal signal = new Signal(
                        symbol,
                        "LONG",
                        currentPrice,
                        BigDecimal.valueOf(rsi),
                        BigDecimal.valueOf(sessionLow),
                        BigDecimal.valueOf(momentum),
                        inBuyZone
                );

                signalRepository.save(signal);

                // Execute trade
                tradeManager.executeLongEntry(signal);

            } else {
                logger.info("No signal. Conditions - RSI Oversold: {}, Buy Zone: {}, Strong Momentum: {}",
                        rsiOversoldCondition, buyZoneCondition, momentumCondition);

                // Save HOLD signal for record
                Signal signal = new Signal(
                        symbol,
                        "HOLD",
                        currentPrice,
                        BigDecimal.valueOf(rsi),
                        BigDecimal.valueOf(sessionLow),
                        BigDecimal.valueOf(momentum),
                        inBuyZone
                );
                signalRepository.save(signal);
            }

        } catch (Exception e) {
            logger.error("Error executing strategy: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Manual trigger for strategy execution (for testing)
     */
    public void executeStrategyManual() {
        logger.info("Manual strategy execution triggered");
        executeStrategy();
    }

    /**
     * Monitor open trades for stop loss / take profit
     */
    @Scheduled(fixedRate = 60000) // Every 1 minute
    public void monitorOpenTrades() {
        if (!strategyEnabled) {
            return;
        }

        try {
            tradeManager.monitorAndCloseTrades();
        } catch (Exception e) {
            logger.error("Error monitoring trades: {}", e.getMessage(), e);
        }
    }

    /**
     * Get current strategy status
     */
    public String getStrategyStatus() {
        return String.format("Strategy: BTCUSD 15m SOLO LONG | Enabled: %s | Symbol: %s | " +
                        "RSI(%d) < %.0f | Lookback: %d | Killzone: %.1f%% | Min Momentum: %.1f%%",
                strategyEnabled, symbol, rsiLength, rsiOversold, 
                lookbackBars, killzoneThreshold, minMomentum);
    }
}
