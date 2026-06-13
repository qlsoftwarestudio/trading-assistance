package com.trading.assistant.execution;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.notification.TelegramBot;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.model.TradeJournal;
import com.trading.assistant.portfolio.repository.TradeJournalRepository;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.IndicatorCalculator;
import com.trading.assistant.strategy.model.PriceProjection;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TradeManager {

    private static final Logger logger = LoggerFactory.getLogger(TradeManager.class);

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private TelegramBot telegramBot;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private IndicatorCalculator indicatorCalculator;

    @Autowired
    private TradeJournalRepository tradeJournalRepository;

    @Value("${trading.strategy.stop-loss-pct:2.0}")
    private double stopLossPct;

    @Value("${trading.strategy.use-atr-stop:false}")
    private boolean useAtrStop;

    @Value("${trading.strategy.atr-period:14}")
    private int atrPeriod;

    @Value("${trading.strategy.atr-multiplier:2.0}")
    private double atrMultiplier;

    @Value("${trading.strategy.take-profit-pct:8.0}")
    private double takeProfitPct;

    @Value("${trading.strategy.position-size-pct:20.0}")
    private double positionSizePct;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.leverage:5}")
    private int leverage;

    @Value("${trading.strategy.max-concurrent-trades:2}")
    private int maxConcurrentTrades;

    @Value("${trading.strategy.max-hold-minutes:20}")
    private int maxHoldMinutes;

    @Value("${trading.strategy.trailing-stop-pct:0.6}")
    private double trailingStopPct;

    @Value("${trading.strategy.trailing-activation-pct:0.6}")
    private double trailingActivationPct;

    @Value("${trading.strategy.breakeven-activation-pct:0.4}")
    private double breakevenActivationPct;

    @Value("${trading.strategy.min-profit-pct:0.08}")
    private double minProfitPct;

    @Value("${trading.strategy.time-based-trail-pct:0.5}")
    private double timeBasedTrailPct;

    @Value("${trading.strategy.time-threshold-min:10}")
    private int timeThresholdMin;

    @Value("${trading.strategy.sl-cooldown-minutes:10}")
    private int slCooldownMinutes;

    @Value("${trading.risk.max-daily-loss-pct:5.0}")
    private double maxDailyLossPct;

    @Value("${trading.risk.min-notional:5.0}")
    private double minNotional;

    @Value("${trading.strategy.projection-candles-ahead:6}")
    private int projectionCandlesAhead;

    // Dynamic position sizing based on volatility
    @Value("${trading.strategy.position-size-volatility-adjust:false}")
    private boolean positionSizeVolatilityAdjust;

    @Value("${trading.strategy.position-size-atr-lookback:50}")
    private int positionSizeAtrLookback;

    @Value("${trading.strategy.position-size-high-vol-factor:0.5}")
    private double positionSizeHighVolFactor;

    @Value("${trading.strategy.position-size-low-vol-factor:1.5}")
    private double positionSizeLowVolFactor;

    @Value("${trading.strategy.position-size-volatility-threshold:1.5}")
    private double positionSizeVolatilityThreshold;

    // Momentum exit: close trade if momentum stalls
    @Value("${trading.strategy.momentum-exit-enabled:true}")
    private boolean momentumExitEnabled;

    @Value("${trading.strategy.momentum-exit-drop-pct:70.0}")
    private double momentumExitDropPct;

    @Value("${trading.strategy.momentum-exit-progress-threshold:0.5}")
    private double momentumExitProgressThreshold;

    @Value("${trading.strategy.momentum-exit-min-hold-minutes:15}")
    private int momentumExitMinHoldMinutes;

    private final Map<String, LocalDateTime> lastSlTime = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> tradePeakPrices = new ConcurrentHashMap<>();
    private final Map<Long, Double> tradeEntryMomentum = new ConcurrentHashMap<>();
    private final Map<Long, JournalEntryData> tradeJournalData = new ConcurrentHashMap<>();

    /**
     * Simple data holder for journal entry conditions captured at trade entry.
     */
    private static class JournalEntryData {
        String setupType;
        BigDecimal entryRsi;
        BigDecimal entryVolumeRatio;
        BigDecimal entryMomentum;
        String trend1h;
        String trend4h;
        String trend1d;
        BigDecimal atrAtEntry;
        Boolean inBuyZone;
        Boolean inSellZone;
        Boolean vwapFilterPassed;
        Boolean regressionFilterPassed;
    }

    private boolean isDailyLossLimitHit(BigDecimal balance) {
        try {
            LocalDateTime startOfDay = LocalDateTime.now().toLocalDate().atStartOfDay();
            BigDecimal dailyPnl = tradeRepository.calculateDailyPnl(startOfDay);
            BigDecimal limit = balance.multiply(BigDecimal.valueOf(maxDailyLossPct / 100)).negate();
            if (dailyPnl.compareTo(limit) <= 0) {
                logger.warn("🛑 Daily loss limit hit! Daily P&L: ${} / Limit: -{}% (${}).",
                        dailyPnl.setScale(2, RoundingMode.HALF_UP),
                        maxDailyLossPct,
                        limit.abs().setScale(2, RoundingMode.HALF_UP));
                return true;
            }
        } catch (Exception e) {
            logger.warn("Could not check daily P&L: {}", e.getMessage());
        }
        return false;
    }

    private boolean isCoolingDown(String action) {
        LocalDateTime last = lastSlTime.get(action);
        if (last == null) return false;
        long elapsed = Duration.between(last, LocalDateTime.now()).toMinutes();
        if (elapsed < slCooldownMinutes) {
            logger.info("⏸️ Cooldown active for {} entries. {} min remaining (SL {} min ago).",
                    action, slCooldownMinutes - elapsed, elapsed);
            return true;
        }
        return false;
    }

    private BigDecimal calculateFixedStopLoss(BigDecimal currentPrice, boolean isLong) {
        if (isLong) {
            return currentPrice.multiply(BigDecimal.valueOf(1 - stopLossPct / 100)).setScale(8, RoundingMode.HALF_UP);
        } else {
            return currentPrice.multiply(BigDecimal.valueOf(1 + stopLossPct / 100)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    private BigDecimal calculateFixedTakeProfit(BigDecimal currentPrice, boolean isLong) {
        if (isLong) {
            return currentPrice.multiply(BigDecimal.valueOf(1 + takeProfitPct / 100)).setScale(8, RoundingMode.HALF_UP);
        } else {
            return currentPrice.multiply(BigDecimal.valueOf(1 - takeProfitPct / 100)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * Check if there's an open position for given direction (LONG/SHORT)
     */
    public boolean hasOpenPosition(String action) {
        return tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN")
                .stream()
                .anyMatch(t -> t.getAction().equals(action));
    }

    /**
     * Execute LONG entry based on signal
     */
    public void executeLongEntry(Signal signal) {
        executeEntry(signal, "LONG", binanceClient::placeBuyOrder);
    }

    /**
     * Execute SHORT entry based on signal
     */
    public void executeShortEntry(Signal signal) {
        executeEntry(signal, "SHORT", binanceClient::placeShortSellOrder);
    }

    // ============== SCALP / HUNTER ENTRIES ==============

    @Value("${trading.strategy.hunter.sl-pct:0.1}")
    private double hunterSlPct;

    @Value("${trading.strategy.hunter.tp-pct:0.3}")
    private double hunterTpPct;

    @Value("${trading.strategy.hunter.position-size-pct:50}")
    private double hunterPositionSizePct;

    @Value("${trading.strategy.hunter.max-concurrent:1}")
    private int hunterMaxConcurrent;

    @Value("${trading.strategy.hunter.max-hold-minutes:3}")
    private int hunterMaxHoldMinutes;

    @Value("${trading.strategy.hunter.trailing-activation:0.15}")
    private double hunterTrailingActivation;

    @Value("${trading.strategy.hunter.trailing-pct:0.05}")
    private double hunterTrailingPct;

    /**
     * Execute SCALP LONG entry — tight SL/TP, smaller position, no ATR.
     * Called from ScalpStrategy when 1m conditions are met.
     */
    public void executeScalpLongEntry(BigDecimal currentPrice, String setupType,
                                       double rsi, double momentum, double volRatio) {
        executeScalpEntry(currentPrice, "LONG", setupType, rsi, momentum, volRatio,
                binanceClient::placeBuyOrder);
    }

    /**
     * Execute SCALP SHORT entry — tight SL/TP, smaller position, no ATR.
     * Called from ScalpStrategy when 1m conditions are met.
     */
    public void executeScalpShortEntry(BigDecimal currentPrice, String setupType,
                                        double rsi, double momentum, double volRatio) {
        executeScalpEntry(currentPrice, "SHORT", setupType, rsi, momentum, volRatio,
                binanceClient::placeShortSellOrder);
    }

    private void executeScalpEntry(BigDecimal currentPrice, String action, String setupType,
                                    double rsi, double momentum, double volRatio,
                                    OrderPlacer orderPlacer) {
        try {
            BigDecimal balance = binanceClient.getBalance("USDT");

            // Check daily loss limit
            if (isDailyLossLimitHit(balance)) {
                return;
            }

            // Check SL cooldown
            if (isCoolingDown(action)) {
                return;
            }

            // Check capacity: allow 1 LONG scalp + 1 SHORT scalp simultaneously
            long scalpSameDir = 0;
            long totalScalps = 0;
            long swingCount = 0;
            for (Trade t : tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN")) {
                boolean isScalp = t.getSetupType() != null && t.getSetupType().startsWith("SCALP_");
                if (isScalp) {
                    totalScalps++;
                    if (action.equals(t.getAction())) scalpSameDir++;
                } else {
                    swingCount++;
                }
            }
            if (swingCount >= maxConcurrentTrades) {
                logger.info("Max swing trades reached ({}/{}). Skipping scalp {} entry.",
                        swingCount, maxConcurrentTrades, action);
                return;
            }
            if (scalpSameDir >= 1) {
                logger.info("Scalp {} already open ({}/1). Skipping.", action, scalpSameDir);
                return;
            }
            if (totalScalps >= hunterMaxConcurrent) {
                logger.info("Max scalp trades reached ({}/{}). Skipping scalp {} entry.",
                        totalScalps, hunterMaxConcurrent, action);
                return;
            }

            // Position size: smaller than swing trades
            BigDecimal rawPositionSize = balance
                    .multiply(BigDecimal.valueOf(positionSizePct))
                    .multiply(BigDecimal.valueOf(hunterPositionSizePct / 100.0))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

            // Single scalp uses full allocated size (no slot splitting like swing)
            BigDecimal positionSize = rawPositionSize;

            logger.info("🎯 Scalp capital allocation - Balance: ${}, Pos size: ${} ({}% × {}% hunter)",
                    balance, positionSize, positionSizePct, hunterPositionSizePct);

            // Calculate quantity (consider leverage)
            BigDecimal notional = positionSize.multiply(BigDecimal.valueOf(leverage));
            BigDecimal quantity = notional.divide(currentPrice, 8, RoundingMode.HALF_DOWN);

            // Validate minimum notional
            if (notional.compareTo(BigDecimal.valueOf(minNotional)) < 0) {
                logger.warn("⚠️ Scalp notional too small: ${} < ${} min. Skipping.",
                        notional.setScale(2, RoundingMode.HALF_UP), minNotional);
                return;
            }

            // Fixed SL/TP for scalps (no ATR)
            boolean isLong = "LONG".equals(action);
            BigDecimal stopLoss;
            BigDecimal takeProfit;
            if (isLong) {
                stopLoss = currentPrice.multiply(BigDecimal.valueOf(1 - hunterSlPct / 100)).setScale(8, RoundingMode.HALF_UP);
                takeProfit = currentPrice.multiply(BigDecimal.valueOf(1 + hunterTpPct / 100)).setScale(8, RoundingMode.HALF_UP);
            } else {
                stopLoss = currentPrice.multiply(BigDecimal.valueOf(1 + hunterSlPct / 100)).setScale(8, RoundingMode.HALF_UP);
                takeProfit = currentPrice.multiply(BigDecimal.valueOf(1 - hunterTpPct / 100)).setScale(8, RoundingMode.HALF_UP);
            }

            logger.info("🎯 Executing scalp {} entry - Price: {}, Qty: {}, SL: {} ({}%), TP: {} ({}%)",
                    action, currentPrice, quantity, stopLoss, hunterSlPct, takeProfit, hunterTpPct);

            // Place market order
            String orderId = orderPlacer.place(quantity);

            if (orderId != null) {
                Trade trade = new Trade(
                        symbol,
                        action,
                        currentPrice,
                        quantity,
                        positionSize,
                        stopLoss,
                        takeProfit
                );
                trade.setBinanceOrderId(orderId);
                trade.setSetupType(setupType);
                tradeRepository.save(trade);

                // Initialize peak tracking for trailing
                tradePeakPrices.put(trade.getId(), currentPrice);
                tradeEntryMomentum.put(trade.getId(), momentum);

                // Journal data
                JournalEntryData jed = new JournalEntryData();
                jed.setupType = setupType;
                jed.entryRsi = BigDecimal.valueOf(rsi);
                jed.entryVolumeRatio = BigDecimal.valueOf(volRatio);
                jed.entryMomentum = BigDecimal.valueOf(momentum);
                tradeJournalData.put(trade.getId(), jed);

                // Place conditional SL/TP
                String slSide = isLong ? "SELL" : "BUY";
                String tpSide = isLong ? "SELL" : "BUY";
                String positionSide = isLong ? "LONG" : "SHORT";

                String slOrderId = binanceClient.placeStopLossOrder(slSide, positionSide, quantity, stopLoss);
                String tpOrderId = binanceClient.placeTakeProfitOrder(tpSide, positionSide, quantity, takeProfit);

                if (slOrderId != null && tpOrderId != null) {
                    trade.setStopLossOrderId(slOrderId);
                    trade.setTakeProfitOrderId(tpOrderId);
                    tradeRepository.save(trade);
                    logger.info("🔗 Scalp conditional orders placed for Trade {}: SL={}, TP={}",
                            trade.getId(), slOrderId, tpOrderId);
                } else {
                    logger.warn("⚠️ Failed to place scalp conditional orders for Trade {}.", trade.getId());
                }

                telegramBot.sendTradeNotification(trade, "SCALP_ENTRY");

                logger.info("✅ Scalp {} trade executed. Trade ID: {}, Order ID: {}",
                        action, trade.getId(), orderId);
            } else {
                logger.error("❌ Failed to execute scalp {} order on Binance", action);
            }

        } catch (Exception e) {
            logger.error("Error executing scalp {} entry: {}", action, e.getMessage(), e);
        }
    }

    private void executeEntry(Signal signal, String action, OrderPlacer orderPlacer) {
        try {
            BigDecimal currentPrice = signal.getPrice();
            BigDecimal balance = binanceClient.getBalance("USDT");

            // Check daily loss limit
            if (isDailyLossLimitHit(balance)) {
                return;
            }

            // Check SL cooldown
            if (isCoolingDown(action)) {
                return;
            }

            // Check concurrent trade limit
            long openCount = tradeRepository.countByStatus("OPEN");
            if (openCount >= maxConcurrentTrades) {
                logger.info("Max concurrent trades reached ({}/{}). Skipping {} entry.",
                        openCount, maxConcurrentTrades, action);
                return;
            }

            // Calculate position size: distribute available capital among remaining slots
            int remainingSlots = maxConcurrentTrades - (int) openCount;

            // Volatility-adjusted position sizing
            double effectivePositionSizePct = positionSizePct;
            if (positionSizeVolatilityAdjust) {
                effectivePositionSizePct = calculateVolatilityAdjustedPositionSizePct();
            }

            BigDecimal rawPositionSize = balance
                    .multiply(BigDecimal.valueOf(effectivePositionSizePct))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            BigDecimal positionSize = rawPositionSize
                    .divide(BigDecimal.valueOf(remainingSlots), 8, RoundingMode.HALF_UP);

            logger.info("Capital allocation - Balance: ${}, Raw pos size: ${} ({}%), Slots: {}/{}, Adjusted pos size: ${}",
                    balance, rawPositionSize, String.format("%.1f", effectivePositionSizePct), openCount, maxConcurrentTrades, positionSize);

            // Calculate quantity (consider leverage)
            BigDecimal notional = positionSize.multiply(BigDecimal.valueOf(leverage));
            BigDecimal quantity = notional.divide(currentPrice, 8, RoundingMode.HALF_DOWN);

            // Validate minimum notional
            if (notional.compareTo(BigDecimal.valueOf(minNotional)) < 0) {
                logger.warn("⚠️ Notional too small: ${} < ${} min. Skipping {} entry.",
                        notional.setScale(2, RoundingMode.HALF_UP), minNotional, action);
                return;
            }

            // Calculate stop loss and take profit
            BigDecimal stopLoss;
            BigDecimal takeProfit;
            boolean isLong = "LONG".equals(action);

            if (useAtrStop) {
                List<Kline> klines = binanceClient.getKlines(symbol, "5m", atrPeriod + 5);
                double atr = indicatorCalculator.calculateATR(klines, atrPeriod);
                if (atr > 0) {
                    stopLoss = indicatorCalculator.atrBasedStopLoss(currentPrice, atr, (int) Math.round(atrMultiplier), isLong)
                            .setScale(8, RoundingMode.HALF_UP);
                    // TP = 2x risk (reward/risk = 2:1)
                    BigDecimal risk = isLong ? currentPrice.subtract(stopLoss) : stopLoss.subtract(currentPrice);
                    if (isLong) {
                        takeProfit = currentPrice.add(risk.multiply(BigDecimal.valueOf(2)));
                    } else {
                        takeProfit = currentPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
                    }
                    takeProfit = takeProfit.setScale(8, RoundingMode.HALF_UP);
                    logger.info("ATR-based SL/TP for {}: ATR={:.4f}, SL={} ({}%), TP={} ({}%)",
                            action, atr, stopLoss,
                            indicatorCalculator.distanceToLevelPct(currentPrice, stopLoss),
                            takeProfit,
                            indicatorCalculator.distanceToLevelPct(currentPrice, takeProfit));
                } else {
                    logger.warn("ATR calculation failed, falling back to fixed pct stop");
                    stopLoss = calculateFixedStopLoss(currentPrice, isLong);
                    takeProfit = calculateFixedTakeProfit(currentPrice, isLong);
                }
            } else {
                stopLoss = calculateFixedStopLoss(currentPrice, isLong);
                takeProfit = calculateFixedTakeProfit(currentPrice, isLong);
            }

            logger.info("Executing {} entry - Price: {}, Quantity: {}, SL: {}, TP: {}",
                    action, currentPrice, quantity, stopLoss, takeProfit);

            // Place order on Binance
            String orderId = orderPlacer.place(quantity);

            if (orderId != null) {
                Trade trade = new Trade(
                        symbol,
                        action,
                        currentPrice,
                        quantity,
                        positionSize,
                        stopLoss,
                        takeProfit
                );
                trade.setBinanceOrderId(orderId);
                trade.setSetupType(signal.getSetupType());
                tradeRepository.save(trade);

                // Initialize trailing stop tracking and momentum tracking
                tradePeakPrices.put(trade.getId(), currentPrice);
                if (signal.getMomentum() != null) {
                    tradeEntryMomentum.put(trade.getId(), signal.getMomentum().doubleValue());
                }

                // Capture journal entry data for learning
                JournalEntryData jed = new JournalEntryData();
                jed.setupType = signal.getSetupType();
                jed.entryRsi = signal.getRsi();
                jed.entryVolumeRatio = signal.getRelativeVolume();
                jed.entryMomentum = signal.getMomentum();
                jed.trend1h = signal.getTrend1h();
                jed.trend4h = signal.getTrend4h();
                jed.trend1d = signal.getTrend1d();
                jed.inBuyZone = signal.getInBuyZone();
                jed.inSellZone = signal.getInSellZone();
                if (useAtrStop) {
                    List<Kline> klines = binanceClient.getKlines(symbol, "5m", atrPeriod + 5);
                    double atr = indicatorCalculator.calculateATR(klines, atrPeriod);
                    if (atr > 0) {
                        jed.atrAtEntry = BigDecimal.valueOf(atr);
                    }
                }
                tradeJournalData.put(trade.getId(), jed);

                // Place conditional SL and TP orders on Binance (server-side execution)
                String slSide = isLong ? "SELL" : "BUY";
                String tpSide = isLong ? "SELL" : "BUY";
                String positionSide = isLong ? "LONG" : "SHORT";

                String slOrderId = binanceClient.placeStopLossOrder(slSide, positionSide, quantity, stopLoss);
                String tpOrderId = binanceClient.placeTakeProfitOrder(tpSide, positionSide, quantity, takeProfit);

                if (slOrderId != null && tpOrderId != null) {
                    trade.setStopLossOrderId(slOrderId);
                    trade.setTakeProfitOrderId(tpOrderId);
                    tradeRepository.save(trade);
                    logger.info("🔗 Conditional orders placed for Trade {}: SL order={}, TP order={}",
                            trade.getId(), slOrderId, tpOrderId);
                } else {
                    logger.warn("⚠️ Failed to place conditional orders for Trade {}. SL={}, TP={}",
                            trade.getId(), slOrderId, tpOrderId);
                }

                signal.setExecuted(true);
                signal.setTradeId(trade.getId());
                signalRepository.save(signal);

                telegramBot.sendTradeNotification(trade, "ENTRY");

                logger.info("✅ {} trade executed successfully. Trade ID: {}, Order ID: {}",
                        action, trade.getId(), orderId);
            } else {
                logger.error("❌ Failed to execute {} order on Binance", action);
            }

        } catch (Exception e) {
            logger.error("Error executing {} entry: {}", action, e.getMessage(), e);
        }
    }

    /**
     * Update trailing stops and time exits using already-fetched data (no extra REST calls).
     * Called from executeStrategy every 2 minutes.
     */
    public void updateTrailingAndTimeExit(BigDecimal currentPrice, Kline currentKline, PriceProjection projection) {
        List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
        if (openTrades == null || openTrades.isEmpty()) return;
        for (Trade trade : openTrades) {
            // Safety: ensure conditional orders exist on Binance (handles testnet fallback delays or failures)
            ensureConditionalOrders(trade);
            updateTrailingStop(trade, currentPrice, currentKline, projection);
            checkMomentumExit(trade, currentPrice, currentKline);
            checkTimeExit(trade, currentPrice);
        }
    }

    /**
     * Fast polling (10s) to check SL/TP for open trades.
     * Critical for testnet where Binance conditional orders are not supported.
     * In production, Binance handles this server-side, but this still provides safety.
     */
    @Scheduled(fixedRate = 10000)
    public void monitorOpenTradesSLTP() {
        try {
            List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
            if (openTrades == null || openTrades.isEmpty()) return;
            BigDecimal currentPrice = binanceClient.getCurrentPrice();
            if (currentPrice == null) return;
            for (Trade trade : openTrades) {
                checkPriceAgainstSLTP(trade, currentPrice);
            }
        } catch (Exception e) {
            logger.debug("Error in monitorOpenTradesSLTP: {}", e.getMessage());
        }
    }

    private boolean checkPriceAgainstSLTP(Trade trade, BigDecimal currentPrice) {
        try {
            if (trade.getStopLoss() == null || trade.getTakeProfit() == null) return false;
            boolean isLong = "LONG".equals(trade.getAction());

            // Check Stop Loss
            if (isLong) {
                if (currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                    logger.info("⛔ Local SL hit for LONG Trade {}. Price {} <= SL {}",
                            trade.getId(), currentPrice, trade.getStopLoss());
                    closeTrade(trade, currentPrice, "STOP_LOSS");
                    return true;
                }
            } else {
                if (currentPrice.compareTo(trade.getStopLoss()) >= 0) {
                    logger.info("⛔ Local SL hit for SHORT Trade {}. Price {} >= SL {}",
                            trade.getId(), currentPrice, trade.getStopLoss());
                    closeTrade(trade, currentPrice, "STOP_LOSS");
                    return true;
                }
            }

            // Check Take Profit
            if (isLong) {
                if (currentPrice.compareTo(trade.getTakeProfit()) >= 0) {
                    logger.info("🎯 Local TP hit for LONG Trade {}. Price {} >= TP {}",
                            trade.getId(), currentPrice, trade.getTakeProfit());
                    closeTrade(trade, currentPrice, "TAKE_PROFIT");
                    return true;
                }
            } else {
                if (currentPrice.compareTo(trade.getTakeProfit()) <= 0) {
                    logger.info("🎯 Local TP hit for SHORT Trade {}. Price {} <= TP {}",
                            trade.getId(), currentPrice, trade.getTakeProfit());
                    closeTrade(trade, currentPrice, "TAKE_PROFIT");
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            logger.error("Error checking SL/TP for Trade {}: {}", trade.getId(), e.getMessage());
            return false;
        }
    }

    private void ensureConditionalOrders(Trade trade) {
        try {
            boolean isLong = "LONG".equals(trade.getAction());
            String slSide = isLong ? "SELL" : "BUY";
            String tpSide = isLong ? "SELL" : "BUY";
            String positionSide = isLong ? "LONG" : "SHORT";
            BigDecimal quantity = trade.getQuantity();

            if (trade.getStopLossOrderId() == null || trade.getStopLossOrderId().isEmpty()) {
                logger.warn("Missing SL order for Trade {} — creating now", trade.getId());
                String slOrderId = binanceClient.placeStopLossOrder(slSide, positionSide, quantity, trade.getStopLoss());
                if (slOrderId != null) {
                    trade.setStopLossOrderId(slOrderId);
                    tradeRepository.save(trade);
                    logger.info("✅ Created missing SL order for Trade {}: orderId={}", trade.getId(), slOrderId);
                } else {
                    logger.error("CRITICAL: Failed to create missing SL order for Trade {}", trade.getId());
                }
            }

            if (trade.getTakeProfitOrderId() == null || trade.getTakeProfitOrderId().isEmpty()) {
                logger.warn("Missing TP order for Trade {} — creating now", trade.getId());
                String tpOrderId = binanceClient.placeTakeProfitOrder(tpSide, positionSide, quantity, trade.getTakeProfit());
                if (tpOrderId != null) {
                    trade.setTakeProfitOrderId(tpOrderId);
                    tradeRepository.save(trade);
                    logger.info("✅ Created missing TP order for Trade {}: orderId={}", trade.getId(), tpOrderId);
                } else {
                    logger.error("CRITICAL: Failed to create missing TP order for Trade {}", trade.getId());
                }
            }
        } catch (Exception e) {
            logger.error("Error ensuring conditional orders for Trade {}: {}", trade.getId(), e.getMessage());
        }
    }

    private void updateTrailingStop(Trade trade, BigDecimal currentPrice, Kline currentKline, PriceProjection projection) {
        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal stopLoss = trade.getStopLoss();
        boolean isShort = "SHORT".equals(trade.getAction());
        LocalDateTime entryTime = trade.getEntryTime();

        if (entryPrice == null || stopLoss == null || trailingStopPct <= 0) return;

        // Detect scalp trades: setupType starts with SCALP_
        JournalEntryData journal = tradeJournalData.get(trade.getId());
        boolean isScalp = journal != null && journal.setupType != null && journal.setupType.startsWith("SCALP_");

        if (isScalp) {
            updateScalpTrailingStop(trade, currentPrice, entryPrice, stopLoss, isShort, entryTime);
            return;
        }

        boolean tpReachable = projection != null &&
                (isShort ? projection.isTpReachableShort() : projection.isTpReachableLong());
        if (tpReachable) {
            logger.debug("Trade {}: TP within ATR range — trailing stop bypassed", trade.getId());
            return;
        }

        BigDecimal peak = tradePeakPrices.getOrDefault(trade.getId(), entryPrice);

        if (currentKline != null) {
            if (isShort) {
                BigDecimal klineLow = currentKline.getLow();
                if (klineLow != null && klineLow.compareTo(peak) < 0) peak = klineLow;
            } else {
                BigDecimal klineHigh = currentKline.getHigh();
                if (klineHigh != null && klineHigh.compareTo(peak) > 0) peak = klineHigh;
            }
            tradePeakPrices.put(trade.getId(), peak);
        } else {
            if (isShort) {
                if (currentPrice.compareTo(peak) < 0) peak = currentPrice;
            } else {
                if (currentPrice.compareTo(peak) > 0) peak = currentPrice;
            }
            tradePeakPrices.put(trade.getId(), peak);
        }

        double favorableMove = isShort
                ? entryPrice.doubleValue() - peak.doubleValue()
                : peak.doubleValue() - entryPrice.doubleValue();
        double movePct = favorableMove / entryPrice.doubleValue() * 100.0;

        double breakevenThreshold = entryPrice.doubleValue() * breakevenActivationPct / 100.0;
        double activationThreshold = entryPrice.doubleValue() * trailingActivationPct / 100.0;

        BigDecimal breakevenSL = isShort
                ? entryPrice.multiply(BigDecimal.valueOf(1 - minProfitPct / 100.0)).setScale(8, RoundingMode.HALF_UP)
                : entryPrice.multiply(BigDecimal.valueOf(1 + minProfitPct / 100.0)).setScale(8, RoundingMode.HALF_UP);

        // Phase 1: Breakeven lock
        if (favorableMove >= breakevenThreshold && favorableMove < activationThreshold) {
            boolean shouldUpdate = isShort ? breakevenSL.compareTo(stopLoss) < 0 : breakevenSL.compareTo(stopLoss) > 0;
            if (shouldUpdate) {
                trade.setStopLoss(breakevenSL);
                tradeRepository.save(trade);
                updateBinanceStopLossOrder(trade);
                logger.info("Breakeven lock for {} Trade {}. SL: {} (entry: {}, min-profit: {}%)",
                        isShort ? "SHORT" : "LONG", trade.getId(), breakevenSL, entryPrice, minProfitPct);
            }
        }

        // Phase 2+3: Trailing stop
        if (favorableMove >= activationThreshold) {
            double dynamicTrailPct;
            if (movePct >= 1.0) {
                dynamicTrailPct = 0.25;
            } else if (movePct < 0.8) {
                long heldMin = Duration.between(entryTime, LocalDateTime.now()).toMinutes();
                dynamicTrailPct = (heldMin < timeThresholdMin) ? timeBasedTrailPct : 0.3;
            } else {
                dynamicTrailPct = 0.3;
            }
            BigDecimal trailingDistance = peak.multiply(BigDecimal.valueOf(dynamicTrailPct / 100));

            BigDecimal newSL;
            BigDecimal effectiveSL;
            if (isShort) {
                newSL = peak.add(trailingDistance);
                effectiveSL = newSL.min(breakevenSL);
                if (effectiveSL.compareTo(stopLoss) < 0) {
                    trade.setStopLoss(effectiveSL);
                    tradeRepository.save(trade);
                    updateBinanceStopLossOrder(trade);
                    logger.info("Trailing stop tightened for SHORT Trade {}. SL: {} (peak: {}, move: -{}%, trail: {}%, floor: {})",
                            trade.getId(), effectiveSL, peak, String.format("%.3f", movePct), String.format("%.2f", dynamicTrailPct), breakevenSL);
                }
            } else {
                newSL = peak.subtract(trailingDistance);
                effectiveSL = newSL.max(breakevenSL);
                if (effectiveSL.compareTo(stopLoss) > 0) {
                    trade.setStopLoss(effectiveSL);
                    tradeRepository.save(trade);
                    updateBinanceStopLossOrder(trade);
                    logger.info("Trailing stop raised for LONG Trade {}. SL: {} (peak: {}, move: +{}%, trail: {}%, floor: {})",
                            trade.getId(), effectiveSL, peak, String.format("%.3f", movePct), String.format("%.2f", dynamicTrailPct), breakevenSL);
                }
            }
        } else if (favorableMove < breakevenThreshold) {
            logger.debug("Trailing/Breakeven not yet active for Trade {}. Favorable move: {}% (need {}%)",
                    trade.getId(), String.format("%.3f", movePct), String.format("%.1f", breakevenActivationPct));
        }
    }

    /**
     * Ultra-fast trailing stop for scalp trades.
     * Activation at 0.15%, trail distance 0.05%.
     * No breakeven phase — jumps straight to trailing when profitable.
     */
    private void updateScalpTrailingStop(Trade trade, BigDecimal currentPrice,
                                          BigDecimal entryPrice, BigDecimal stopLoss,
                                          boolean isShort, LocalDateTime entryTime) {
        BigDecimal peak = tradePeakPrices.getOrDefault(trade.getId(), entryPrice);

        if (isShort) {
            if (currentPrice.compareTo(peak) < 0) peak = currentPrice;
        } else {
            if (currentPrice.compareTo(peak) > 0) peak = currentPrice;
        }
        tradePeakPrices.put(trade.getId(), peak);

        double favorableMove = isShort
                ? entryPrice.doubleValue() - peak.doubleValue()
                : peak.doubleValue() - entryPrice.doubleValue();
        double movePct = favorableMove / entryPrice.doubleValue() * 100.0;

        double activationThreshold = entryPrice.doubleValue() * hunterTrailingActivation / 100.0;

        if (favorableMove >= activationThreshold) {
            BigDecimal trailingDistance = peak.multiply(BigDecimal.valueOf(hunterTrailingPct / 100));
            BigDecimal newSL;
            if (isShort) {
                newSL = peak.add(trailingDistance).setScale(8, RoundingMode.HALF_UP);
                if (newSL.compareTo(stopLoss) < 0) {
                    trade.setStopLoss(newSL);
                    tradeRepository.save(trade);
                    updateBinanceStopLossOrder(trade);
                    logger.info("🎯 Scalp trailing tightened for SHORT Trade {}. SL: {} (peak: {}, move: -{}%, trail: {}%)",
                            trade.getId(), newSL, peak, String.format("%.3f", movePct), String.format("%.2f", hunterTrailingPct));
                }
            } else {
                newSL = peak.subtract(trailingDistance).setScale(8, RoundingMode.HALF_UP);
                if (newSL.compareTo(stopLoss) > 0) {
                    trade.setStopLoss(newSL);
                    tradeRepository.save(trade);
                    updateBinanceStopLossOrder(trade);
                    logger.info("🎯 Scalp trailing raised for LONG Trade {}. SL: {} (peak: {}, move: +{}%, trail: {}%)",
                            trade.getId(), newSL, peak, String.format("%.3f", movePct), String.format("%.2f", hunterTrailingPct));
                }
            }
        } else {
            logger.debug("Scalp trailing not yet active for Trade {}. Move: {}% (need {}%)",
                    trade.getId(), String.format("%.3f", movePct), String.format("%.2f", hunterTrailingActivation));
        }
    }

    private void updateBinanceStopLossOrder(Trade trade) {
        try {
            String oldSlOrderId = trade.getStopLossOrderId();
            boolean isLong = "LONG".equals(trade.getAction());
            String slSide = isLong ? "SELL" : "BUY";
            String positionSide = isLong ? "LONG" : "SHORT";

            // 1. Place NEW SL order FIRST (never leave trade unprotected)
            String newSlOrderId = binanceClient.placeStopLossOrder(slSide, positionSide, trade.getQuantity(), trade.getStopLoss());
            if (newSlOrderId != null) {
                trade.setStopLossOrderId(newSlOrderId);
                tradeRepository.save(trade);
                logger.info("Updated Binance SL order for Trade {}: new orderId={}", trade.getId(), newSlOrderId);

                // 2. Only cancel OLD order after new one is confirmed
                if (oldSlOrderId != null && !oldSlOrderId.isEmpty()) {
                    boolean cancelled = binanceClient.cancelOrder(oldSlOrderId);
                    if (!cancelled) {
                        logger.warn("Failed to cancel old SL order {} for Trade {} — harmless, new SL is active", oldSlOrderId, trade.getId());
                    }
                }
            } else {
                logger.error("CRITICAL: Failed to place new SL order for Trade {}. Old SL still active: {}", trade.getId(), oldSlOrderId);
            }
        } catch (Exception e) {
            logger.error("Error updating Binance SL order for Trade {}: {}", trade.getId(), e.getMessage());
        }
    }

    private void checkTimeExit(Trade trade, BigDecimal currentPrice) {
        LocalDateTime entryTime = trade.getEntryTime();
        if (entryTime == null) return;
        Duration held = Duration.between(entryTime, LocalDateTime.now());

        // Scalp trades: much shorter max hold time
        JournalEntryData journal = tradeJournalData.get(trade.getId());
        boolean isScalp = journal != null && journal.setupType != null && journal.setupType.startsWith("SCALP_");
        int effectiveMaxHold = isScalp ? hunterMaxHoldMinutes : maxHoldMinutes;

        if (held.toMinutes() >= effectiveMaxHold) {
            logger.info("⏱️ Time exit for {}Trade {}. Held: {} min (max: {} min). Current: {}, Entry: {}",
                    isScalp ? "SCALP " : "", trade.getId(), held.toMinutes(), effectiveMaxHold, currentPrice, trade.getEntryPrice());
            closeTrade(trade, currentPrice, "TIME_EXIT");
        }
    }

    /**
     * Momentum exit: close trade if momentum has stalled significantly.
     * Only evaluates after min hold time (default 15 min) to avoid killing trades on early noise.
     */
    private void checkMomentumExit(Trade trade, BigDecimal currentPrice, Kline currentKline) {
        if (!momentumExitEnabled) return;
        if (currentKline == null) return;

        // Require minimum hold time before evaluating momentum exit
        LocalDateTime entryTime = trade.getEntryTime();
        if (entryTime == null) return;
        long heldMin = Duration.between(entryTime, LocalDateTime.now()).toMinutes();
        if (heldMin < momentumExitMinHoldMinutes) {
            return;
        }

        Double entryMomentum = tradeEntryMomentum.get(trade.getId());
        if (entryMomentum == null || entryMomentum == 0.0) return;

        // Calculate current momentum from current kline
        double currentMomentum = currentKline.getClose()
                .subtract(currentKline.getOpen())
                .divide(currentKline.getOpen(), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();

        // For LONG: momentum should remain positive. For SHORT: negative.
        boolean isLong = "LONG".equals(trade.getAction());
        double momentumDrop;
        if (isLong) {
            if (entryMomentum > 0) {
                momentumDrop = (entryMomentum - Math.max(0, currentMomentum)) / entryMomentum;
            } else {
                momentumDrop = 1.0; // Entry momentum was negative for LONG = bad
            }
        } else {
            if (entryMomentum < 0) {
                momentumDrop = (Math.abs(entryMomentum) - Math.max(0, -currentMomentum)) / Math.abs(entryMomentum);
            } else {
                momentumDrop = 1.0; // Entry momentum was positive for SHORT = bad
            }
        }

        // Check progress toward TP
        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal takeProfit = trade.getTakeProfit();
        double progressToTp;
        if (isLong) {
            progressToTp = currentPrice.subtract(entryPrice).doubleValue()
                    / takeProfit.subtract(entryPrice).doubleValue();
        } else {
            progressToTp = entryPrice.subtract(currentPrice).doubleValue()
                    / entryPrice.subtract(takeProfit).doubleValue();
        }

        if (momentumDrop >= momentumExitDropPct / 100.0 && progressToTp < momentumExitProgressThreshold) {
            logger.info("📉 Momentum exit for Trade {}. EntryMo: {}%, CurrentMo: {}%, Drop: {}%, ProgressToTP: {}%, Held: {}min",
                    trade.getId(),
                    String.format("%.4f", entryMomentum),
                    String.format("%.4f", currentMomentum),
                    String.format("%.0f", momentumDrop * 100),
                    String.format("%.1f", progressToTp * 100),
                    heldMin);
            closeTrade(trade, currentPrice, "MOMENTUM_EXIT");
        }
    }

    /**
     * Handle order updates from Binance User Data Stream (WebSocket).
     * Called when SL or TP is executed server-side.
     */
    public void handleOrderUpdate(String orderId, String orderType, String status, String avgPrice) {
        try {
            if (orderId == null || orderId.isEmpty()) return;
            // Find trade by SL or TP order ID
            Trade trade = null;
            List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
            for (Trade t : openTrades) {
                if (orderId.equals(t.getStopLossOrderId()) || orderId.equals(t.getTakeProfitOrderId())) {
                    trade = t;
                    break;
                }
            }
            if (trade == null) {
                logger.warn("Received WS order update for unknown orderId: {} (type={})", orderId, orderType);
                return;
            }

            BigDecimal exitPrice = new BigDecimal(avgPrice);
            String reason;
            if ("STOP_MARKET".equalsIgnoreCase(orderType) || orderId.equals(trade.getStopLossOrderId())) {
                reason = "TRAILING_STOP".equals(trade.getExitReason()) ? "TRAILING_STOP" : "STOP_LOSS";
            } else {
                reason = "TAKE_PROFIT";
            }

            logger.info("� WS Event: {} executed for Trade {} at price={}", reason, trade.getId(), exitPrice);
            closeTradeFromEvent(trade, exitPrice, reason);
        } catch (Exception e) {
            logger.error("Error handling WS order update: {}", e.getMessage(), e);
        }
    }

    private void closeTradeFromEvent(Trade trade, BigDecimal exitPrice, String reason) {
        try {
            // Cancel remaining conditional orders
            if (trade.getStopLossOrderId() != null && !trade.getStopLossOrderId().equals(trade.getBinanceOrderId())) {
                binanceClient.cancelOrder(trade.getStopLossOrderId());
            }
            if (trade.getTakeProfitOrderId() != null && !trade.getTakeProfitOrderId().equals(trade.getBinanceOrderId())) {
                binanceClient.cancelOrder(trade.getTakeProfitOrderId());
            }

            BigDecimal commission = trade.getInvestedAmount()
                    .multiply(BigDecimal.valueOf(0.0012))
                    .setScale(8, RoundingMode.HALF_UP);

            trade.close(exitPrice, reason, commission);
            tradeRepository.save(trade);

            if ("STOP_LOSS".equals(reason)) {
                lastSlTime.put(trade.getAction(), LocalDateTime.now());
            }

            tradePeakPrices.remove(trade.getId());
            telegramBot.sendTradeNotification(trade, "EXIT");

            logger.info("✅ Trade {} closed from WS event. Reason: {}, P&L: ${} ({}%)",
                    trade.getId(), reason, trade.getPnl(), trade.getPnlPercent());
        } catch (Exception e) {
            logger.error("Error closing trade {} from event: {}", trade.getId(), e.getMessage(), e);
        }
    }

    private void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        try {
            // Cancel remaining conditional orders first (so Binance doesn't fire SL/TP after manual close)
            if (trade.getStopLossOrderId() != null && !trade.getStopLossOrderId().equals(trade.getBinanceOrderId())) {
                binanceClient.cancelOrder(trade.getStopLossOrderId());
            }
            if (trade.getTakeProfitOrderId() != null && !trade.getTakeProfitOrderId().equals(trade.getBinanceOrderId())) {
                binanceClient.cancelOrder(trade.getTakeProfitOrderId());
            }

            String orderId;
            if ("SHORT".equals(trade.getAction())) {
                orderId = binanceClient.placeShortBuyOrder(trade.getQuantity());
            } else {
                orderId = binanceClient.placeSellOrder(trade.getQuantity());
            }

            if (orderId != null) {
                BigDecimal commission = trade.getInvestedAmount()
                        .multiply(BigDecimal.valueOf(0.0012))
                        .setScale(8, RoundingMode.HALF_UP);

                trade.close(exitPrice, reason, commission);
                tradeRepository.save(trade);

                if ("STOP_LOSS".equals(reason)) {
                    lastSlTime.put(trade.getAction(), LocalDateTime.now());
                    logger.info("⏸️ SL cooldown started for {} - no new {} entries for {} min",
                            trade.getAction(), trade.getAction(), slCooldownMinutes);
                }

                tradePeakPrices.remove(trade.getId());
                tradeEntryMomentum.remove(trade.getId());

                // Save to trade journal for learning
                saveTradeJournal(trade, reason);

                telegramBot.sendTradeNotification(trade, "EXIT");

                logger.info("✅ Trade {} closed. Reason: {}, P&L: ${} ({}%)",
                        trade.getId(), reason, trade.getPnl(), trade.getPnlPercent());
            } else {
                logger.error("❌ Failed to close trade {} on Binance", trade.getId());
            }

        } catch (Exception e) {
            logger.error("Error closing trade {}: {}", trade.getId(), e.getMessage(), e);
        }
    }

    /**
     * Save trade outcome to journal for learning and auto-adjustment.
     */
    private void saveTradeJournal(Trade trade, String reason) {
        try {
            JournalEntryData jed = tradeJournalData.remove(trade.getId());
            if (jed == null) return;

            TradeJournal journal = new TradeJournal();
            journal.setTradeId(trade.getId());
            journal.setSymbol(trade.getSymbol());
            journal.setAction(trade.getAction());
            journal.setSetupType(jed.setupType);
            journal.setEntryPrice(trade.getEntryPrice());
            journal.setExitPrice(trade.getExitPrice());
            journal.setPnl(trade.getPnl());
            journal.setPnlPercent(trade.getPnlPercent());
            journal.setEntryRsi(jed.entryRsi);
            journal.setEntryVolumeRatio(jed.entryVolumeRatio);
            journal.setEntryMomentum(jed.entryMomentum);
            journal.setTrend1h(jed.trend1h);
            journal.setTrend4h(jed.trend4h);
            journal.setTrend1d(jed.trend1d);
            journal.setAtrAtEntry(jed.atrAtEntry);

            // Calculate SL/TP distance percentages
            if (trade.getEntryPrice() != null && trade.getStopLoss() != null && trade.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal slDist = trade.getStopLoss().subtract(trade.getEntryPrice()).abs()
                        .divide(trade.getEntryPrice(), 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                journal.setSlDistancePct(slDist);
            }
            if (trade.getEntryPrice() != null && trade.getTakeProfit() != null && trade.getEntryPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal tpDist = trade.getTakeProfit().subtract(trade.getEntryPrice()).abs()
                        .divide(trade.getEntryPrice(), 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                journal.setTpDistancePct(tpDist);
            }

            journal.setInBuyZone(jed.inBuyZone);
            journal.setInSellZone(jed.inSellZone);
            journal.setExitReason(reason);
            journal.setExitTime(LocalDateTime.now());

            tradeJournalRepository.save(journal);
            logger.info("📓 Journal entry saved for Trade {}: setup={}, P&L=${} ({}%)",
                    trade.getId(), jed.setupType, trade.getPnl(), trade.getPnlPercent());
        } catch (Exception e) {
            logger.error("Error saving trade journal for Trade {}: {}", trade.getId(), e.getMessage());
        }
    }

    /**
     * Calculate volatility-adjusted position size percentage.
     * When ATR is high (volatile market), reduce position size to limit risk.
     * When ATR is low (calm market), increase position size to maximize returns.
     */
    private double calculateVolatilityAdjustedPositionSizePct() {
        try {
            List<Kline> klines = binanceClient.getKlines(symbol, "5m", positionSizeAtrLookback + 5);
            if (klines == null || klines.size() < positionSizeAtrLookback) {
                logger.debug("Not enough klines for volatility adjustment, using default size");
                return positionSizePct;
            }

            // Current ATR (recent volatility)
            double currentAtr = indicatorCalculator.calculateATR(
                    klines.subList(klines.size() - atrPeriod - 1, klines.size()), atrPeriod);

            // Reference ATR (longer-term median volatility)
            double referenceAtr = indicatorCalculator.calculateATR(klines, atrPeriod);

            if (referenceAtr <= 0 || currentAtr <= 0) {
                return positionSizePct;
            }

            double ratio = currentAtr / referenceAtr;
            double factor;

            if (ratio >= positionSizeVolatilityThreshold) {
                // High volatility: reduce size
                factor = positionSizeHighVolFactor;
                logger.info("📉 High volatility detected (ATR ratio={:.2f}), reducing position size by {:.0f}%",
                        ratio, (1 - factor) * 100);
            } else if (ratio <= 1.0 / positionSizeVolatilityThreshold) {
                // Low volatility: increase size (but not more than 50% above base)
                factor = positionSizeLowVolFactor;
                logger.info("📈 Low volatility detected (ATR ratio={:.2f}), increasing position size by {:.0f}%",
                        ratio, (factor - 1) * 100);
            } else {
                // Normal volatility: use base size
                factor = 1.0;
            }

            return positionSizePct * factor;

        } catch (Exception e) {
            logger.warn("Error calculating volatility-adjusted size: {}. Using default.", e.getMessage());
            return positionSizePct;
        }
    }

    @FunctionalInterface
    private interface OrderPlacer {
        String place(BigDecimal quantity);
    }

    /**
     * Get summary of current trading status
     */
    public String getTradingStatus() {
        long openTrades = tradeRepository.countByStatus("OPEN");
        long closedTrades = tradeRepository.countByStatus("CLOSED");

        Optional<Trade> lastTrade = tradeRepository.findFirstByStatusOrderByEntryTimeDesc("OPEN");

        StringBuilder status = new StringBuilder();
        status.append(String.format("Open Trades: %d | Closed Trades: %d", openTrades, closedTrades));

        if (lastTrade.isPresent()) {
            Trade trade = lastTrade.get();
            status.append(String.format(" | Last Entry: %s @ $%s", trade.getSymbol(), trade.getEntryPrice()));
        }

        return status.toString();
    }
}
