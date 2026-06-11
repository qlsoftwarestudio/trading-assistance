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

    @Value("${trading.strategy.trailing-activation-pct:0.3}")
    private double trailingActivationPct;

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
     * Monitor open trades and close if SL or TP hit
     */
    public void monitorAndCloseTrades() {
        try {
            List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
            BigDecimal currentPrice = binanceClient.getCurrentPrice();
            List<Kline> klines = binanceClient.getKlines(symbol, "5m", atrPeriod + 5);
            Kline currentKline = (klines != null && !klines.isEmpty()) ? klines.get(klines.size() - 1) : null;

            PriceProjection projection = indicatorCalculator.calculatePriceProjection(
                    klines, atrPeriod, projectionCandlesAhead, takeProfitPct);

            for (Trade trade : openTrades) {
                checkAndCloseTrade(trade, currentPrice, currentKline, projection);
            }

        } catch (Exception e) {
            logger.error("Error monitoring trades: {}", e.getMessage(), e);
        }
    }

    private void checkAndCloseTrade(Trade trade, BigDecimal currentPrice, Kline currentKline, PriceProjection projection) {
        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal stopLoss = trade.getStopLoss();
        BigDecimal takeProfit = trade.getTakeProfit();
        boolean isShort = "SHORT".equals(trade.getAction());
        LocalDateTime entryTime = trade.getEntryTime();

        // Trailing stop: skip if TP is projected reachable within ATR range (let trade run freely to TP)
        // Only activate trailing when volatility is too low to reach TP (capture partial gains instead)
        boolean tpReachable = projection != null &&
                (isShort ? projection.isTpReachableShort() : projection.isTpReachableLong());
        if (tpReachable) {
            logger.debug("Trade {}: TP within ATR range [{}–{}] — trailing stop bypassed, running to TP",
                    trade.getId(),
                    String.format("%.4f", projection.getProjectedLow()),
                    String.format("%.4f", projection.getProjectedHigh()));
        }

        // Trailing stop: track favorable price movement and raise/lower SL
        // Only activates once price moves favorably by at least trailingActivationPct from entry
        if (!tpReachable && trailingStopPct > 0 && entryPrice != null && stopLoss != null) {
            BigDecimal peak = tradePeakPrices.getOrDefault(trade.getId(), entryPrice);

            // Update peak with current kline high/low to catch intrabar moves
            if (currentKline != null) {
                if (isShort) {
                    BigDecimal klineLow = currentKline.getLow();
                    if (klineLow != null && klineLow.compareTo(peak) < 0) {
                        peak = klineLow;
                    }
                } else {
                    BigDecimal klineHigh = currentKline.getHigh();
                    if (klineHigh != null && klineHigh.compareTo(peak) > 0) {
                        peak = klineHigh;
                    }
                }
                tradePeakPrices.put(trade.getId(), peak);
            } else {
                // Fallback to currentPrice snapshot
                if (isShort) {
                    if (currentPrice.compareTo(peak) < 0) {
                        peak = currentPrice;
                    }
                } else {
                    if (currentPrice.compareTo(peak) > 0) {
                        peak = currentPrice;
                    }
                }
                tradePeakPrices.put(trade.getId(), peak);
            }

            // Check activation threshold: only move SL if favorable move >= trailingActivationPct
            double activationThreshold = entryPrice.doubleValue() * trailingActivationPct / 100.0;
            double favorableMove = isShort
                    ? entryPrice.doubleValue() - peak.doubleValue()
                    : peak.doubleValue() - entryPrice.doubleValue();

            if (favorableMove >= activationThreshold) {
                double movePct = favorableMove / entryPrice.doubleValue() * 100.0;
                double dynamicTrailPct;
                if (movePct >= 1.0) {
                    dynamicTrailPct = 0.25;
                } else if (movePct >= 0.5) {
                    dynamicTrailPct = 0.4;
                } else {
                    dynamicTrailPct = trailingStopPct;
                }
                BigDecimal trailingDistance = peak.multiply(BigDecimal.valueOf(dynamicTrailPct / 100));

                if (isShort) {
                    BigDecimal newSL = peak.add(trailingDistance);
                    if (newSL.compareTo(stopLoss) < 0) {
                        trade.setStopLoss(newSL);
                        tradeRepository.save(trade);
                        logger.info("Trailing stop tightened for SHORT Trade {}. SL: {} (peak: {}, favorable move: -{}%, trail: {}%)",
                                trade.getId(), newSL, peak, String.format("%.3f", movePct), String.format("%.2f", dynamicTrailPct));
                    }
                } else {
                    BigDecimal newSL = peak.subtract(trailingDistance);
                    if (newSL.compareTo(stopLoss) > 0) {
                        trade.setStopLoss(newSL);
                        tradeRepository.save(trade);
                        logger.info("Trailing stop raised for LONG Trade {}. SL: {} (peak: {}, move: +{}%, trail: {}%)",
                                trade.getId(), newSL, peak, String.format("%.3f", movePct), String.format("%.2f", dynamicTrailPct));
                    }
                }
            } else {
                logger.debug("Trailing stop not yet active for Trade {}. Favorable move: {}% (need {}%)",
                        trade.getId(), String.format("%.3f", favorableMove / entryPrice.doubleValue() * 100), String.format("%.1f", trailingActivationPct));
            }
        }
        // Time-based exit: close if held longer than maxHoldMinutes
        if (entryTime != null) {
            Duration held = Duration.between(entryTime, LocalDateTime.now());
            if (held.toMinutes() >= maxHoldMinutes) {
                logger.info("⏱️ Time exit for Trade {}. Held: {} min (max: {} min). Current: {}, Entry: {}",
                        trade.getId(), held.toMinutes(), maxHoldMinutes, currentPrice, entryPrice);
                closeTrade(trade, currentPrice, "TIME_EXIT");
                return;
            }
        }

        // For SHORT: SL is above entry, TP is below entry
        if (isShort) {
            if (currentPrice.compareTo(stopLoss) >= 0) {
                logger.info("🛑 Stop Loss hit for SHORT Trade {}. Current: {}, SL: {}",
                        trade.getId(), currentPrice, stopLoss);
                closeTrade(trade, currentPrice, "STOP_LOSS");
                return;
            }
            if (currentPrice.compareTo(takeProfit) <= 0) {
                logger.info("🎯 Take Profit hit for SHORT Trade {}. Current: {}, TP: {}",
                        trade.getId(), currentPrice, takeProfit);
                closeTrade(trade, currentPrice, "TAKE_PROFIT");
                return;
            }
        } else {
            if (currentPrice.compareTo(stopLoss) <= 0) {
                logger.info("🛑 Stop Loss hit for LONG Trade {}. Current: {}, SL: {}",
                        trade.getId(), currentPrice, stopLoss);
                closeTrade(trade, currentPrice, "STOP_LOSS");
                return;
            }
            if (currentPrice.compareTo(takeProfit) >= 0) {
                logger.info("🎯 Take Profit hit for LONG Trade {}. Current: {}, TP: {}",
                        trade.getId(), currentPrice, takeProfit);
                closeTrade(trade, currentPrice, "TAKE_PROFIT");
                return;
            }
        }

        // Log monitoring (promote to INFO so it shows in Railway)
        BigDecimal pnl = isShort
                ? entryPrice.subtract(currentPrice).multiply(trade.getQuantity())
                : currentPrice.subtract(entryPrice).multiply(trade.getQuantity());
        BigDecimal pnlPercent = isShort
                ? entryPrice.subtract(currentPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : currentPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        logger.info("📊 Monitoring Trade {} - Current: {}, Entry: {}, P&L: ${} ({}%), SL: {}, TP: {}",
                trade.getId(), currentPrice, entryPrice, pnl.setScale(4, RoundingMode.HALF_UP),
                pnlPercent.setScale(2, RoundingMode.HALF_UP), stopLoss, takeProfit);
    }

    private void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        try {
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
