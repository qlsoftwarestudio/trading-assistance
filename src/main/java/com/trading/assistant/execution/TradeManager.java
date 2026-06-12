package com.trading.assistant.execution;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.binance.model.Kline;
import com.trading.assistant.notification.TelegramBot;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.IndicatorCalculator;
import com.trading.assistant.strategy.model.PriceProjection;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private final Map<String, LocalDateTime> lastSlTime = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> tradePeakPrices = new ConcurrentHashMap<>();

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
            BigDecimal rawPositionSize = balance
                    .multiply(BigDecimal.valueOf(positionSizePct))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            BigDecimal positionSize = rawPositionSize
                    .divide(BigDecimal.valueOf(remainingSlots), 8, RoundingMode.HALF_UP);

            logger.info("Capital allocation - Balance: ${}, Raw pos size: ${}, Slots: {}/{}, Adjusted pos size: ${}",
                    balance, rawPositionSize, openCount, maxConcurrentTrades, positionSize);

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
                List<Kline> klines = binanceClient.getKlines(symbol, "15m", atrPeriod + 5);
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
                tradeRepository.save(trade);

                // Initialize trailing stop tracking
                tradePeakPrices.put(trade.getId(), currentPrice);

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

                if (signal.getProjectionNote() != null && !signal.getProjectionNote().isEmpty()) {
                    telegramBot.sendAlert("Proyección de precio", signal.getProjectionNote());
                }

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
            checkTimeExit(trade, currentPrice);
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
        if (held.toMinutes() >= maxHoldMinutes) {
            logger.info("⏱️ Time exit for Trade {}. Held: {} min (max: {} min). Current: {}, Entry: {}",
                    trade.getId(), held.toMinutes(), maxHoldMinutes, currentPrice, trade.getEntryPrice());
            closeTrade(trade, currentPrice, "TIME_EXIT");
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
