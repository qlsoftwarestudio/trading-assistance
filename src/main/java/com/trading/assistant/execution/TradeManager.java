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
import com.trading.assistant.user.model.Bot;
import com.trading.assistant.user.model.User;
import com.trading.assistant.user.repository.BotRepository;
import com.trading.assistant.user.repository.UserRepository;
import com.trading.assistant.user.service.EncryptionService;
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

    @Autowired
    private BotRepository botRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private UserRepository userRepository;

    @Value("${trading.strategy.stop-loss-pct:2.0}")
    private double stopLossPct;

    @Value("${trading.strategy.use-atr-stop:false}")
    private boolean useAtrStop;

    @Value("${trading.strategy.atr-period:14}")
    private int atrPeriod;

    @Value("${trading.strategy.atr-multiplier:2.0}")
    private double atrMultiplier;

    @Value("${trading.strategy.max-atr-sl-pct:2.5}")
    private double maxAtrSlPct;

    @Value("${trading.strategy.take-profit-pct:8.0}")
    private double takeProfitPct;

    @Value("${trading.strategy.position-size-pct:20.0}")
    private double positionSizePct;

    @Value("${trading.strategy.symbol:HYPEUSDT}")
    private String symbol;

    @Value("${trading.strategy.symbol-risk-config:}")
    private String symbolRiskConfig;

    private final Map<String, SymbolRisk> symbolRiskMap = new ConcurrentHashMap<>();

    @Value("${trading.strategy.leverage:5}")
    private int leverage;

    @Value("${trading.strategy.max-concurrent-trades:2}")
    private int maxConcurrentTrades;

    @Value("${trading.strategy.max-hold-minutes:45}")
    private int maxHoldMinutes;

    // Dynamic max hold per symbol (HYPE=45min, SOL=120min, etc.)
    private final Map<String, Integer> maxHoldMinutesBySymbol = java.util.Map.of(
            "HYPEUSDT", 45,
            "SOLUSDT", 120,
            "BTCUSDT", 120,
            "ETHUSDT", 90
    );

    // Swing trailing (5m strategy) — wider trail to let trends breathe
    @Value("${trading.strategy.swing.trailing-stop-pct:1.5}")
    private double swingTrailingStopPct;

    @Value("${trading.strategy.swing.trailing-activation-pct:1.0}")
    private double swingTrailingActivationPct;

    // Legacy — kept for backward compat, not used for swing (swing uses above)
    @Value("${trading.strategy.trailing-stop-pct:0.6}")
    private double trailingStopPct;

    @Value("${trading.strategy.trailing-activation-pct:0.6}")
    private double trailingActivationPct;

    @Value("${trading.strategy.breakeven-activation-pct:0.4}")
    private double breakevenActivationPct;

    @Value("${trading.strategy.min-profit-pct:0.08}")
    private double minProfitPct;

    @Value("${trading.strategy.min-sl-update-distance-pct:0.1}")
    private double minSlUpdateDistancePct;

    @Value("${trading.strategy.time-based-trail-pct:0.5}")
    private double timeBasedTrailPct;

    @Value("${trading.strategy.time-threshold-min:10}")
    private int timeThresholdMin;

    @Value("${trading.strategy.sl-cooldown-minutes:10}")
    private int slCooldownMinutes;

    @Value("${trading.risk.max-daily-loss-pct:5.0}")
    private double maxDailyLossPct;

    @Value("${app.risk.max-capital-per-user-usd:500.0}")
    private double maxCapitalPerUserUsd;

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

    @Value("${trading.strategy.use-bb-based-sl:false}")
    private boolean useBbBasedSl;

    @Value("${trading.strategy.bb-sl-buffer-pct:0.1}")
    private double bbSlBufferPct;

    private final Map<String, LocalDateTime> lastSlTime = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> tradePeakPrices = new ConcurrentHashMap<>();
    private final Map<Long, Double> tradeEntryMomentum = new ConcurrentHashMap<>();
    private final Map<Long, JournalEntryData> tradeJournalData = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    public void initSymbolRiskConfig() {
        if (symbolRiskConfig == null || symbolRiskConfig.isBlank()) return;
        for (String entry : symbolRiskConfig.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 3) {
                try {
                    String sym = parts[0].trim();
                    double sl = Double.parseDouble(parts[1].trim());
                    double tp = Double.parseDouble(parts[2].trim());
                    symbolRiskMap.put(sym, new SymbolRisk(sl, tp));
                    logger.info("Symbol risk config loaded: {} -> SL {}%, TP {}%", sym, sl, tp);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid symbol-risk-config entry: {}", entry);
                }
            }
        }
    }

    @jakarta.annotation.PostConstruct
    public void cleanupOrphanedConditionalOrders() {
        try {
            List<Trade> closedWithOrders = tradeRepository.findClosedTradesWithConditionalOrders();
            if (closedWithOrders == null || closedWithOrders.isEmpty()) return;
            int cleaned = 0;
            for (Trade trade : closedWithOrders) {
                String tradeSymbol = trade.getSymbol() != null ? trade.getSymbol() : symbol;
                String[] botCreds = resolveBotCredentials(trade.getUserId(), tradeSymbol);
                try {
                    if (trade.getStopLossOrderId() != null && !trade.getStopLossOrderId().equals(trade.getBinanceOrderId())) {
                        boolean ok = false;
                        try {
                            ok = (botCreds != null)
                                    ? binanceClient.cancelOrderForBot(trade.getStopLossOrderId(), botCreds[0], botCreds[1], tradeSymbol)
                                    : binanceClient.cancelOrder(trade.getStopLossOrderId());
                        } catch (Exception ce) {
                            // 401/404 means order doesn't exist or creds mismatch — treat as "already gone"
                            logger.warn("Cleanup: could not cancel SL order {} for Trade {} ({}), treating as cleared",
                                    trade.getStopLossOrderId(), trade.getId(), ce.getMessage());
                        }
                        // Always clear from DB: if cancel succeeded OR failed (order already gone from Binance)
                        trade.setStopLossOrderId(null);
                        cleaned++;
                    }
                    if (trade.getTakeProfitOrderId() != null && !trade.getTakeProfitOrderId().equals(trade.getBinanceOrderId())) {
                        boolean ok = false;
                        try {
                            ok = (botCreds != null)
                                    ? binanceClient.cancelOrderForBot(trade.getTakeProfitOrderId(), botCreds[0], botCreds[1], tradeSymbol)
                                    : binanceClient.cancelOrder(trade.getTakeProfitOrderId());
                        } catch (Exception ce) {
                            logger.warn("Cleanup: could not cancel TP order {} for Trade {} ({}), treating as cleared",
                                    trade.getTakeProfitOrderId(), trade.getId(), ce.getMessage());
                        }
                        trade.setTakeProfitOrderId(null);
                        cleaned++;
                    }
                    tradeRepository.save(trade);
                } catch (Exception ex) {
                    logger.warn("Cleanup: failed to process closed Trade {}: {}", trade.getId(), ex.getMessage());
                }
            }
            if (cleaned > 0) {
                logger.info("🧹 Cleaned up {} orphaned conditional orders from {} closed trades", cleaned, closedWithOrders.size());
            }
        } catch (Exception e) {
            logger.error("Error during orphaned order cleanup: {}", e.getMessage());
        }
    }

    /**
     * Per-symbol SL/TP override.
     */
    private static class SymbolRisk {
        final double stopLossPct;
        final double takeProfitPct;
        SymbolRisk(double stopLossPct, double takeProfitPct) {
            this.stopLossPct = stopLossPct;
            this.takeProfitPct = takeProfitPct;
        }
    }

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

    private BigDecimal calculateFixedStopLoss(BigDecimal currentPrice, boolean isLong, String tradeSymbol) {
        double slPct = stopLossPct;
        SymbolRisk override = tradeSymbol != null ? symbolRiskMap.get(tradeSymbol) : null;
        if (override != null) {
            slPct = override.stopLossPct;
        }
        if (isLong) {
            return currentPrice.multiply(BigDecimal.valueOf(1 - slPct / 100)).setScale(8, RoundingMode.HALF_UP);
        } else {
            return currentPrice.multiply(BigDecimal.valueOf(1 + slPct / 100)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    private BigDecimal calculateFixedTakeProfit(BigDecimal currentPrice, boolean isLong, String tradeSymbol) {
        double tpPct = takeProfitPct;
        SymbolRisk override = tradeSymbol != null ? symbolRiskMap.get(tradeSymbol) : null;
        if (override != null) {
            tpPct = override.takeProfitPct;
        }
        if (isLong) {
            return currentPrice.multiply(BigDecimal.valueOf(1 + tpPct / 100)).setScale(8, RoundingMode.HALF_UP);
        } else {
            return currentPrice.multiply(BigDecimal.valueOf(1 - tpPct / 100)).setScale(8, RoundingMode.HALF_UP);
        }
    }

    /**
     * Check if there's an open position for given direction (LONG/SHORT) scoped to userId+symbol.
     * Isolation: each user can have their own LONG/SHORT per symbol independently.
     */
    public boolean hasOpenPosition(String symbol, Long userId, String action) {
        return tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(userId, symbol, "OPEN")
                .stream()
                .anyMatch(t -> t.getAction().equals(action));
    }

    /**
     * Resolve bot-specific Binance credentials for order placement.
     * Returns null array if no bot credentials found (falls back to server keys).
     */
    private String[] resolveBotCredentials(Long userId, String sym) {
        if (userId == null) return null;
        try {
            List<Bot> bots = botRepository.findByEnabledTrueAndRunningTrue();
            return bots.stream()
                    .filter(b -> userId.equals(b.getUser() != null ? b.getUser().getId() : null)
                              && sym.equals(b.getSymbol()))
                    .findFirst()
                    .map(b -> new String[]{
                            encryptionService.decrypt(b.getApiKeyEncrypted()),
                            encryptionService.decrypt(b.getApiSecretEncrypted())
                    })
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Could not resolve bot credentials for userId={} sym={}: {}", userId, sym, e.getMessage());
            return null;
        }
    }

    /**
     * Get max capital limit for a user based on their subscription plan.
     * Falls back to app config default if user not found.
     */
    private double getMaxCapitalForUser(Long userId) {
        if (userId == null) return maxCapitalPerUserUsd;
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null && user.getPlan() != null) {
                double planMax = user.getPlan().getMaxCapitalUsd();
                if (planMax == 0.0) {
                    return Double.MAX_VALUE; // ENTERPRISE = unlimited
                }
                return planMax > 0 ? planMax : maxCapitalPerUserUsd;
            }
        } catch (Exception e) {
            logger.warn("Could not resolve plan for userId={}: {}. Using default ${}.", userId, e.getMessage(), maxCapitalPerUserUsd);
        }
        return maxCapitalPerUserUsd;
    }

    /**
     * Execute LONG entry based on signal
     */
    public void executeLongEntry(Signal signal) {
        Long userId = signal.getUserId();
        String sym = signal.getSymbol() != null ? signal.getSymbol().toString() : symbol;
        String[] creds = resolveBotCredentials(userId, sym);
        if (creds != null) {
            try {
                logger.info("Using bot-specific API keys for LONG entry (userId={}, sym={})", userId, sym);
                executeEntry(signal, "LONG", qty -> binanceClient.placeBuyOrderForBot(qty, creds[0], creds[1], sym), creds);
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                    logger.warn("Bot credentials failed with 401 for userId={} sym={}. Falling back to global credentials.", userId, sym);
                    executeEntry(signal, "LONG", binanceClient::placeBuyOrder, null);
                } else {
                    throw e;
                }
            }
        } else {
            executeEntry(signal, "LONG", binanceClient::placeBuyOrder, null);
        }
    }

    /**
     * Execute SHORT entry based on signal
     */
    public void executeShortEntry(Signal signal) {
        Long userId = signal.getUserId();
        String sym = signal.getSymbol() != null ? signal.getSymbol().toString() : symbol;
        String[] creds = resolveBotCredentials(userId, sym);
        if (creds != null) {
            try {
                logger.info("Using bot-specific API keys for SHORT entry (userId={}, sym={})", userId, sym);
                executeEntry(signal, "SHORT", qty -> binanceClient.placeShortSellOrderForBot(qty, creds[0], creds[1], sym), creds);
            } catch (Exception e) {
                if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                    logger.warn("Bot credentials failed with 401 for userId={} sym={}. Falling back to global credentials.", userId, sym);
                    executeEntry(signal, "SHORT", binanceClient::placeShortSellOrder, null);
                } else {
                    throw e;
                }
            }
        } else {
            executeEntry(signal, "SHORT", binanceClient::placeShortSellOrder, null);
        }
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
    public void executeScalpLongEntry(String tradeSymbol, BigDecimal currentPrice, String setupType,
                                       double rsi, double momentum, double volRatio, double atr1m, double atrPct) {
        executeScalpEntry(tradeSymbol, currentPrice, "LONG", setupType, rsi, momentum, volRatio, atr1m, atrPct,
                qty -> binanceClient.placeBuyOrderForSymbol(tradeSymbol, qty));
    }

    /**
     * Execute SCALP SHORT entry — tight SL/TP, smaller position, no ATR.
     * Called from ScalpStrategy when 1m conditions are met.
     */
    public void executeScalpShortEntry(String tradeSymbol, BigDecimal currentPrice, String setupType,
                                        double rsi, double momentum, double volRatio, double atr1m, double atrPct) {
        executeScalpEntry(tradeSymbol, currentPrice, "SHORT", setupType, rsi, momentum, volRatio, atr1m, atrPct,
                qty -> binanceClient.placeShortSellOrderForSymbol(tradeSymbol, qty));
    }

    private void executeScalpEntry(String tradeSymbol, BigDecimal currentPrice, String action, String setupType,
                                    double rsi, double momentum, double volRatio, double atr1m, double atrPct,
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

            // Calculate quantity (consider leverage), rounded to symbol lot size to match Binance execution
            BigDecimal notional = positionSize.multiply(BigDecimal.valueOf(leverage));
            BigDecimal quantity = binanceClient.roundQuantityForSymbol(tradeSymbol,
                    notional.divide(currentPrice, 8, RoundingMode.HALF_DOWN));

            // Validate minimum notional
            if (notional.compareTo(BigDecimal.valueOf(minNotional)) < 0) {
                logger.warn("⚠️ Scalp notional too small: ${} < ${} min. Skipping.",
                        notional.setScale(2, RoundingMode.HALF_UP), minNotional);
                return;
            }

            // ATR-based SL/TP for scalps — calibrated to 1m volatility
            // SL = 2×ATR (gives trade room for 2 typical 1m candles against it)
            // TP = 2×risk (2:1 R:R)
            boolean isLong = "LONG".equals(action);
            BigDecimal stopLoss;
            BigDecimal takeProfit;
            double effectiveSlPct = Math.max(atrPct * 2.0, 0.15);  // min 0.15% floor
            double effectiveTpPct = effectiveSlPct * 2.0;         // 2:1 R:R
            if (isLong) {
                stopLoss = currentPrice.multiply(BigDecimal.valueOf(1 - effectiveSlPct / 100)).setScale(8, RoundingMode.HALF_UP);
                takeProfit = currentPrice.multiply(BigDecimal.valueOf(1 + effectiveTpPct / 100)).setScale(8, RoundingMode.HALF_UP);
            } else {
                stopLoss = currentPrice.multiply(BigDecimal.valueOf(1 + effectiveSlPct / 100)).setScale(8, RoundingMode.HALF_UP);
                takeProfit = currentPrice.multiply(BigDecimal.valueOf(1 - effectiveTpPct / 100)).setScale(8, RoundingMode.HALF_UP);
            }

            logger.info("🎯 Executing scalp {} entry for {} - Price: {}, Qty: {}, SL: {} (ATR={}%, eff={}%), TP: {} (2:1 R:R)",
                    action, tradeSymbol, currentPrice, quantity, stopLoss, String.format("%.3f", atrPct), String.format("%.3f", effectiveSlPct), takeProfit);

            // Place market order
            String orderId = orderPlacer.place(quantity);

            if (orderId != null) {
                Trade trade = new Trade(
                        tradeSymbol,
                        action,
                        currentPrice,
                        quantity,
                        positionSize,
                        stopLoss,
                        takeProfit
                );
                trade.setBinanceOrderId(orderId);
                trade.setSetupType(setupType);
                trade.setUserId(1L); // default admin tenant
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

                String slOrderId = binanceClient.placeStopLossOrderForSymbol(slSide, positionSide, quantity, stopLoss, tradeSymbol);
                String tpOrderId = binanceClient.placeTakeProfitOrderForSymbol(tpSide, positionSide, quantity, takeProfit, tradeSymbol);

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

                logger.info("✅ Scalp {} trade executed for {}. Trade ID: {}, Order ID: {}",
                        action, tradeSymbol, trade.getId(), orderId);
            } else {
                logger.error("❌ Failed to execute scalp {} order on Binance for {}", action, tradeSymbol);
            }

        } catch (Exception e) {
            logger.error("Error executing scalp {} entry for {}: {}", action, tradeSymbol, e.getMessage(), e);
        }
    }

    private void executeEntry(Signal signal, String action, OrderPlacer orderPlacer, String[] botCreds) {
        try {
            BigDecimal currentPrice = signal.getPrice();
            String tradeSymbol = signal.getSymbol() != null ? signal.getSymbol() : symbol;
            Long userId = signal.getUserId() != null ? signal.getUserId() : 1L;

            // Set leverage for this symbol before trading — with 401 fallback to global
            boolean usingBotCreds = false;
            if (botCreds != null) {
                try {
                    binanceClient.setLeverageForBot(tradeSymbol, leverage, botCreds[0], botCreds[1]);
                    usingBotCreds = true;
                } catch (Exception e) {
                    if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                        logger.warn("Bot credentials 401 for leverage on {}. Falling back to global.", tradeSymbol);
                        binanceClient.setLeverageForSymbol(tradeSymbol, leverage);
                        usingBotCreds = false;
                    } else {
                        throw e;
                    }
                }
            } else {
                binanceClient.setLeverageForSymbol(tradeSymbol, leverage);
            }

            // Get balance — with 401 fallback to global
            BigDecimal balance;
            if (usingBotCreds && botCreds != null) {
                try {
                    balance = binanceClient.getBalanceForBot("USDT", botCreds[0], botCreds[1]);
                } catch (Exception e) {
                    if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"))) {
                        logger.warn("Bot credentials 401 for balance. Falling back to global.");
                        balance = binanceClient.getBalance("USDT");
                        usingBotCreds = false;
                    } else {
                        throw e;
                    }
                }
            } else {
                balance = binanceClient.getBalance("USDT");
            }

            // Check daily loss limit
            if (isDailyLossLimitHit(balance)) {
                return;
            }

            // Check SL cooldown
            if (isCoolingDown(action)) {
                return;
            }

            // Check concurrent trade limit — scoped to this user
            long openCount = tradeRepository.countByUserIdAndStatus(userId, "OPEN");
            if (openCount >= maxConcurrentTrades) {
                logger.info("Max concurrent trades reached for userId={} ({}/{}). Skipping {} entry.",
                        userId, openCount, maxConcurrentTrades, action);
                return;
            }

            // Check total exposed capital limit per user (based on plan)
            BigDecimal totalExposed = tradeRepository.calculateTotalInvestedOpenByUserId(userId);
            if (totalExposed == null) totalExposed = BigDecimal.ZERO;
            double maxCapitalUsd = getMaxCapitalForUser(userId);
            BigDecimal maxCapital = BigDecimal.valueOf(maxCapitalUsd);
            if (maxCapital.compareTo(BigDecimal.ZERO) > 0 &&
                    totalExposed.compareTo(maxCapital) >= 0) {
                logger.warn("Capital máximo alcanzado para userId={}. Total expuesto: ${} / ${}. Trade bloqueado.",
                        userId, totalExposed.setScale(2, RoundingMode.HALF_UP),
                        maxCapital.setScale(2, RoundingMode.HALF_UP));
                telegramBot.sendAlert("Capital límite alcanzado",
                        String.format("User %d: total expuesto $%s / $%s. Trade %s bloqueado.",
                                userId, totalExposed.setScale(2, RoundingMode.HALF_UP),
                                maxCapital.setScale(2, RoundingMode.HALF_UP), action));
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

            // Calculate quantity (consider leverage), rounded to symbol lot size to match Binance execution
            BigDecimal notional = positionSize.multiply(BigDecimal.valueOf(leverage));
            BigDecimal quantity = binanceClient.roundQuantityForSymbol(tradeSymbol,
                    notional.divide(currentPrice, 8, RoundingMode.HALF_DOWN));

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
                List<Kline> klines = binanceClient.getKlines(tradeSymbol, "5m", atrPeriod + 5);
                double atr = indicatorCalculator.calculateATR(klines, atrPeriod);
                if (atr > 0) {
                    stopLoss = indicatorCalculator.atrBasedStopLoss(currentPrice, atr, (int) Math.round(atrMultiplier), isLong)
                            .setScale(8, RoundingMode.HALF_UP);
                    // Cap: if ATR-based SL is too far from entry, fall back to fixed SL
                    double slDistancePct = isLong
                            ? (currentPrice.doubleValue() - stopLoss.doubleValue()) / currentPrice.doubleValue() * 100.0
                            : (stopLoss.doubleValue() - currentPrice.doubleValue()) / currentPrice.doubleValue() * 100.0;
                    if (slDistancePct > maxAtrSlPct) {
                        logger.warn("ATR-based SL too wide ({}% > {}% max, ATR={}) — using fixed SL",
                                String.format("%.2f", slDistancePct),
                                String.format("%.1f", maxAtrSlPct),
                                String.format("%.4f", atr));
                        stopLoss = calculateFixedStopLoss(currentPrice, isLong, tradeSymbol);
                        takeProfit = calculateFixedTakeProfit(currentPrice, isLong, tradeSymbol);
                    } else {
                        // TP = 2x risk (reward/risk = 2:1)
                        BigDecimal risk = isLong ? currentPrice.subtract(stopLoss) : stopLoss.subtract(currentPrice);
                        if (isLong) {
                            takeProfit = currentPrice.add(risk.multiply(BigDecimal.valueOf(2)));
                        } else {
                            takeProfit = currentPrice.subtract(risk.multiply(BigDecimal.valueOf(2)));
                        }
                        takeProfit = takeProfit.setScale(8, RoundingMode.HALF_UP);
                        logger.info("ATR-based SL/TP for {}: ATR={}, SL={} ({}%), TP={} ({}%)",
                                action,
                                String.format("%.4f", atr),
                                stopLoss,
                                String.format("%.2f", slDistancePct),
                                takeProfit,
                                String.format("%.2f", slDistancePct * 2));
                    }
                } else {
                    logger.warn("ATR calculation failed, falling back to fixed pct stop");
                    stopLoss = calculateFixedStopLoss(currentPrice, isLong, tradeSymbol);
                    takeProfit = calculateFixedTakeProfit(currentPrice, isLong, tradeSymbol);
                }
            } else {
                stopLoss = calculateFixedStopLoss(currentPrice, isLong, tradeSymbol);
                takeProfit = calculateFixedTakeProfit(currentPrice, isLong, tradeSymbol);
            }

            // BB-based SL: tighten SL to the BB lower/upper band if it's closer to price than ATR SL
            if (useBbBasedSl && signal.getBbLower() != null && signal.getBbUpper() != null) {
                if (isLong) {
                    BigDecimal bbSl = signal.getBbLower()
                            .multiply(BigDecimal.valueOf(1.0 - bbSlBufferPct / 100.0))
                            .setScale(8, RoundingMode.HALF_UP);
                    if (bbSl.compareTo(stopLoss) > 0) {
                        logger.info("📊 BB-based SL tightened for LONG: {} → {} (BB lower: {})", stopLoss, bbSl, signal.getBbLower());
                        stopLoss = bbSl;
                        BigDecimal risk = currentPrice.subtract(stopLoss);
                        takeProfit = currentPrice.add(risk.multiply(BigDecimal.valueOf(2.0))).setScale(8, RoundingMode.HALF_UP);
                    }
                } else {
                    BigDecimal bbSl = signal.getBbUpper()
                            .multiply(BigDecimal.valueOf(1.0 + bbSlBufferPct / 100.0))
                            .setScale(8, RoundingMode.HALF_UP);
                    if (bbSl.compareTo(stopLoss) < 0) {
                        logger.info("📊 BB-based SL tightened for SHORT: {} → {} (BB upper: {})", stopLoss, bbSl, signal.getBbUpper());
                        stopLoss = bbSl;
                        BigDecimal risk = stopLoss.subtract(currentPrice);
                        takeProfit = currentPrice.subtract(risk.multiply(BigDecimal.valueOf(2.0))).setScale(8, RoundingMode.HALF_UP);
                    }
                }
            }

            logger.info("Executing {} entry - Price: {}, Quantity: {}, SL: {}, TP: {}",
                    action, currentPrice, quantity, stopLoss, takeProfit);

            // Place order on Binance
            String orderId = orderPlacer.place(quantity);

            // Fallback to global credentials if bot order failed with 401 (bot creds may be invalid for this symbol)
            if (orderId == null && botCreds != null) {
                logger.warn("Bot order placement failed for {}. Falling back to global credentials.", tradeSymbol);
                if (isLong) {
                    orderId = binanceClient.placeBuyOrder(quantity);
                } else {
                    orderId = binanceClient.placeShortSellOrder(quantity);
                }
                if (orderId != null) {
                    usingBotCreds = false; // Ensure SL/TP also use global
                }
            }

            if (orderId != null) {
                Trade trade = new Trade(
                        tradeSymbol,
                        action,
                        currentPrice,
                        quantity,
                        positionSize,
                        stopLoss,
                        takeProfit
                );
                trade.setBinanceOrderId(orderId);
                trade.setSetupType(signal.getSetupType());
                if (signal.getUserId() != null) trade.setUserId(signal.getUserId());
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
                    List<Kline> klines = binanceClient.getKlines(tradeSymbol, "5m", atrPeriod + 5);
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

                String slOrderId = (usingBotCreds)
                        ? binanceClient.placeStopLossOrderForBot(slSide, positionSide, quantity, stopLoss, botCreds[0], botCreds[1], tradeSymbol)
                        : binanceClient.placeStopLossOrder(slSide, positionSide, quantity, stopLoss);
                String tpOrderId = (usingBotCreds)
                        ? binanceClient.placeTakeProfitOrderForBot(tpSide, positionSide, quantity, takeProfit, botCreds[0], botCreds[1], tradeSymbol)
                        : binanceClient.placeTakeProfitOrder(tpSide, positionSide, quantity, takeProfit);

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
     * Called from executeStrategy every 2 minutes, scoped to userId+symbol for isolation.
     */
    public void updateTrailingAndTimeExit(String symbol, Long userId, BigDecimal currentPrice, Kline currentKline, PriceProjection projection) {
        List<Trade> openTrades = tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(userId, symbol, "OPEN");
        if (openTrades == null || openTrades.isEmpty()) return;
        for (Trade trade : openTrades) {
            // Safety: ensure conditional orders exist on Binance (handles testnet fallback delays or failures)
            ensureConditionalOrders(trade);
            // Extra safety: also check SL/TP locally using the already-fetched price (fallback if monitor/WS fail)
            checkPriceAgainstSLTP(trade, currentPrice);
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
            logger.info("🔍 monitorOpenTradesSLTP running for {} open trade(s)", openTrades.size());
            // Get price per symbol — each trade symbol may differ
            Map<String, BigDecimal> priceCache = new ConcurrentHashMap<>();
            for (Trade trade : openTrades) {
                String sym = trade.getSymbol();
                BigDecimal price = priceCache.computeIfAbsent(sym, s -> binanceClient.getPrice(s));
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    checkPriceAgainstSLTP(trade, price);
                } else {
                    logger.warn("Could not get price for {} in monitorOpenTradesSLTP", sym);
                }
            }
        } catch (Exception e) {
            logger.error("❌ Error in monitorOpenTradesSLTP: {}", e.getMessage(), e);
        }
    }

    private boolean checkPriceAgainstSLTP(Trade trade, BigDecimal currentPrice) {
        try {
            if (trade.getStopLoss() == null || trade.getTakeProfit() == null) return false;
            boolean isLong = "LONG".equals(trade.getAction());

            // Check Stop Loss
            if (isLong) {
                if (currentPrice.compareTo(trade.getStopLoss()) <= 0) {
                    boolean slMoved = trade.getOriginalStopLoss() != null && trade.getStopLoss().compareTo(trade.getOriginalStopLoss()) > 0;
                    boolean profitable = currentPrice.compareTo(trade.getEntryPrice()) > 0;
                    String reason = (slMoved && profitable) ? "TRAILING_STOP" : "STOP_LOSS";
                    // Use stored SL as exit price: Binance algo already fired at that price.
                    // currentPrice may be worse due to polling delay in high-volatility markets.
                    BigDecimal exitPrice = trade.getStopLoss();
                    logger.info("⛔ Local SL hit for LONG Trade {}. Poll price {} <= SL {} → exiting at SL price (original SL: {}, reason: {})",
                            trade.getId(), currentPrice, trade.getStopLoss(), trade.getOriginalStopLoss(), reason);
                    closeTrade(trade, exitPrice, reason);
                    return true;
                }
            } else {
                if (currentPrice.compareTo(trade.getStopLoss()) >= 0) {
                    boolean slMoved = trade.getOriginalStopLoss() != null && trade.getStopLoss().compareTo(trade.getOriginalStopLoss()) < 0;
                    boolean profitable = currentPrice.compareTo(trade.getEntryPrice()) < 0;
                    String reason = (slMoved && profitable) ? "TRAILING_STOP" : "STOP_LOSS";
                    BigDecimal exitPrice = trade.getStopLoss();
                    logger.info("⛔ Local SL hit for SHORT Trade {}. Poll price {} >= SL {} → exiting at SL price (original SL: {}, reason: {})",
                            trade.getId(), currentPrice, trade.getStopLoss(), trade.getOriginalStopLoss(), reason);
                    closeTrade(trade, exitPrice, reason);
                    return true;
                }
            }

            // Check Take Profit
            if (isLong) {
                if (currentPrice.compareTo(trade.getTakeProfit()) >= 0) {
                    logger.info("🎯 Local TP hit for LONG Trade {}. Poll price {} >= TP {} → exiting at TP price",
                            trade.getId(), currentPrice, trade.getTakeProfit());
                    closeTrade(trade, trade.getTakeProfit(), "TAKE_PROFIT");
                    return true;
                }
            } else {
                if (currentPrice.compareTo(trade.getTakeProfit()) <= 0) {
                    logger.info("🎯 Local TP hit for SHORT Trade {}. Poll price {} <= TP {} → exiting at TP price",
                            trade.getId(), currentPrice, trade.getTakeProfit());
                    closeTrade(trade, trade.getTakeProfit(), "TAKE_PROFIT");
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
            String tradeSymbol = trade.getSymbol() != null ? trade.getSymbol() : symbol;
            String[] botCreds = resolveBotCredentials(trade.getUserId(), tradeSymbol);

            if (trade.getStopLossOrderId() == null || trade.getStopLossOrderId().isEmpty()) {
                logger.warn("Missing SL order for Trade {} — creating now", trade.getId());
                String slOrderId = (botCreds != null)
                        ? binanceClient.placeStopLossOrderForBot(slSide, positionSide, quantity, trade.getStopLoss(), botCreds[0], botCreds[1], tradeSymbol)
                        : binanceClient.placeStopLossOrder(slSide, positionSide, quantity, trade.getStopLoss());
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
                String tpOrderId = (botCreds != null)
                        ? binanceClient.placeTakeProfitOrderForBot(tpSide, positionSide, quantity, trade.getTakeProfit(), botCreds[0], botCreds[1], tradeSymbol)
                        : binanceClient.placeTakeProfitOrder(tpSide, positionSide, quantity, trade.getTakeProfit());
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

        double effectiveSwingTrail = swingTrailingStopPct > 0 ? swingTrailingStopPct : trailingStopPct;
        double effectiveSwingActivation = swingTrailingActivationPct > 0 ? swingTrailingActivationPct : trailingActivationPct;
        if (entryPrice == null || stopLoss == null || effectiveSwingTrail <= 0) return;

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
            BigDecimal currentPeak = tradePeakPrices.getOrDefault(trade.getId(), entryPrice);
            double quickMove = isShort
                    ? entryPrice.doubleValue() - currentPeak.doubleValue()
                    : currentPeak.doubleValue() - entryPrice.doubleValue();
            double activationAmt = entryPrice.doubleValue() * effectiveSwingActivation / 100.0;
            if (quickMove < activationAmt) {
                logger.debug("Trade {}: TP within ATR range, trailing not yet active — bypassed", trade.getId());
                return;
            }
            logger.debug("Trade {}: TP within ATR range but trailing already active (+{}%) — continuing",
                    trade.getId(), String.format("%.2f", quickMove / entryPrice.doubleValue() * 100.0));
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
        double activationThreshold = entryPrice.doubleValue() * effectiveSwingActivation / 100.0;

        BigDecimal breakevenSL = isShort
                ? entryPrice.multiply(BigDecimal.valueOf(1 - minProfitPct / 100.0)).setScale(8, RoundingMode.HALF_UP)
                : entryPrice.multiply(BigDecimal.valueOf(1 + minProfitPct / 100.0)).setScale(8, RoundingMode.HALF_UP);

        // Phase 1: Breakeven lock
        if (favorableMove >= breakevenThreshold && favorableMove < activationThreshold) {
            boolean shouldUpdate = isShort ? breakevenSL.compareTo(stopLoss) < 0 : breakevenSL.compareTo(stopLoss) > 0;
            if (shouldUpdate && isSlFarEnough(currentPrice, breakevenSL, isShort)) {
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
            if (movePct >= 1.5) {
                // Big move: very tight trail (0.15% or half of config)
                dynamicTrailPct = Math.max(0.15, effectiveSwingTrail / 2.0);
            } else if (movePct >= 0.8) {
                // Medium move: moderate trail (0.3%)
                dynamicTrailPct = Math.max(0.30, effectiveSwingTrail / 2.0);
            } else {
                // Small move: trail = 1/3 of move to lock at least 2/3 of profits
                // e.g. move=0.6%, trail=0.20% → locks +0.40%
                dynamicTrailPct = Math.max(effectiveSwingTrail / 3.0, movePct * 0.35);
            }
            BigDecimal trailingDistance = peak.multiply(BigDecimal.valueOf(dynamicTrailPct / 100));

            BigDecimal newSL;
            BigDecimal effectiveSL;
            if (isShort) {
                newSL = peak.add(trailingDistance);
                effectiveSL = newSL.min(breakevenSL);
                if (effectiveSL.compareTo(stopLoss) < 0 && isSlFarEnough(currentPrice, effectiveSL, isShort)) {
                    trade.setStopLoss(effectiveSL);
                    tradeRepository.save(trade);
                    updateBinanceStopLossOrder(trade);
                    logger.info("Trailing stop tightened for SHORT Trade {}. SL: {} (peak: {}, move: -{}%, trail: {}%, floor: {})",
                            trade.getId(), effectiveSL, peak, String.format("%.3f", movePct), String.format("%.2f", dynamicTrailPct), breakevenSL);
                }
            } else {
                newSL = peak.subtract(trailingDistance);
                effectiveSL = newSL.max(breakevenSL);
                if (effectiveSL.compareTo(stopLoss) > 0 && isSlFarEnough(currentPrice, effectiveSL, isShort)) {
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
                if (newSL.compareTo(stopLoss) < 0 && isSlFarEnough(currentPrice, newSL, isShort)) {
                    trade.setStopLoss(newSL);
                    tradeRepository.save(trade);
                    updateBinanceStopLossOrder(trade);
                    logger.info("🎯 Scalp trailing tightened for SHORT Trade {}. SL: {} (peak: {}, move: -{}%, trail: {}%)",
                            trade.getId(), newSL, peak, String.format("%.3f", movePct), String.format("%.2f", hunterTrailingPct));
                }
            } else {
                newSL = peak.subtract(trailingDistance).setScale(8, RoundingMode.HALF_UP);
                if (newSL.compareTo(stopLoss) > 0 && isSlFarEnough(currentPrice, newSL, isShort)) {
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

    private boolean isSlFarEnough(BigDecimal currentPrice, BigDecimal newSl, boolean isShort) {
        double distance = isShort
                ? (newSl.doubleValue() - currentPrice.doubleValue()) / currentPrice.doubleValue() * 100.0
                : (currentPrice.doubleValue() - newSl.doubleValue()) / currentPrice.doubleValue() * 100.0;
        if (distance < minSlUpdateDistancePct) {
            logger.info("SL update skipped: too close to price ({}% < {}%)", String.format("%.3f", distance), minSlUpdateDistancePct);
            return false;
        }
        return true;
    }

    private void updateBinanceStopLossOrder(Trade trade) {
        try {
            String oldSlOrderId = trade.getStopLossOrderId();
            boolean isLong = "LONG".equals(trade.getAction());
            String slSide = isLong ? "SELL" : "BUY";
            String positionSide = isLong ? "LONG" : "SHORT";
            String tradeSymbol = trade.getSymbol() != null ? trade.getSymbol() : symbol;
            String[] botCreds = resolveBotCredentials(trade.getUserId(), tradeSymbol);

            // 1. Place NEW SL order FIRST (never leave trade unprotected)
            String newSlOrderId = (botCreds != null)
                    ? binanceClient.placeStopLossOrderForBot(slSide, positionSide, trade.getQuantity(), trade.getStopLoss(), botCreds[0], botCreds[1], tradeSymbol)
                    : binanceClient.placeStopLossOrder(slSide, positionSide, trade.getQuantity(), trade.getStopLoss());
            if (newSlOrderId != null) {
                trade.setStopLossOrderId(newSlOrderId);
                tradeRepository.save(trade);
                logger.info("Updated Binance SL order for Trade {}: new orderId={}", trade.getId(), newSlOrderId);

                // 2. Only cancel OLD order after new one is confirmed
                if (oldSlOrderId != null && !oldSlOrderId.isEmpty()) {
                    boolean cancelled = (botCreds != null)
                            ? binanceClient.cancelOrderForBot(oldSlOrderId, botCreds[0], botCreds[1], tradeSymbol)
                            : binanceClient.cancelOrder(oldSlOrderId);
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
        int symbolMaxHold = maxHoldMinutesBySymbol.getOrDefault(trade.getSymbol(), maxHoldMinutes);
        int effectiveMaxHold = isScalp ? hunterMaxHoldMinutes : symbolMaxHold;

        // Extend time exit when trade is in profit (>0.5%) to avoid cutting winning trades
        boolean isLong = "LONG".equals(trade.getAction());
        double entryPrice = trade.getEntryPrice().doubleValue();
        double current = currentPrice.doubleValue();
        boolean inProfit = isLong ? current > entryPrice : current < entryPrice;
        double movePct = Math.abs((current - entryPrice) / entryPrice * 100.0);
        if (inProfit && movePct > 0.5) {
            effectiveMaxHold += 30;
            logger.info("⏱️ Time exit extended for Trade {}: +30 min (profit {}%, new max: {} min)",
                    trade.getId(), String.format("%.2f", movePct), effectiveMaxHold);
        }

        if (held.toMinutes() >= effectiveMaxHold) {
            logger.info("⏱️ Time exit for {}Trade {}. Held: {} min (max: {} min, symbol: {}). Current: {}, Entry: {}",
                    isScalp ? "SCALP " : "", trade.getId(), held.toMinutes(), effectiveMaxHold, trade.getSymbol(), currentPrice, trade.getEntryPrice());
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
        handleOrderUpdate(orderId, orderType, status, avgPrice, null, null, false);
    }

    public void handleOrderUpdate(String orderId, String orderType, String status, String avgPrice,
                                   String sym, String side, boolean reduceOnly) {
        try {
            if (orderId == null || orderId.isEmpty()) return;
            List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");

            // Primary match: by stored SL/TP order ID (works for regular orders)
            Trade trade = null;
            for (Trade t : openTrades) {
                if (orderId.equals(t.getStopLossOrderId()) || orderId.equals(t.getTakeProfitOrderId())) {
                    trade = t;
                    break;
                }
            }

            // Fallback: algo orders fire with a NEW orderId not stored in DB.
            // Match by symbol + reduce-only + order type to find the affected open trade.
            if (trade == null && sym != null && !sym.isEmpty() && reduceOnly) {
                boolean isStopEvent = "STOP_MARKET".equalsIgnoreCase(orderType) || "STOP".equalsIgnoreCase(orderType);
                boolean isTpEvent   = "TAKE_PROFIT_MARKET".equalsIgnoreCase(orderType) || "TAKE_PROFIT".equalsIgnoreCase(orderType);
                if (isStopEvent || isTpEvent) {
                    for (Trade t : openTrades) {
                        if (sym.equals(t.getSymbol())) {
                            // side of the close order is opposite to trade direction
                            boolean longClose  = "SELL".equalsIgnoreCase(side) && "LONG".equals(t.getAction());
                            boolean shortClose = "BUY".equalsIgnoreCase(side)  && "SHORT".equals(t.getAction());
                            if (longClose || shortClose) {
                                trade = t;
                                logger.info("📡 WS fallback match: Trade {} matched by sym={} side={} type={}",
                                        t.getId(), sym, side, orderType);
                                break;
                            }
                        }
                    }
                }
            }

            if (trade == null) {
                logger.warn("📡 WS order update ignored — no matching open trade. orderId={} type={} sym={} side={}",
                        orderId, orderType, sym, side);
                return;
            }

            BigDecimal exitPrice = (avgPrice != null && !avgPrice.equals("0") && !avgPrice.isEmpty())
                    ? new BigDecimal(avgPrice) : null;
            if (exitPrice == null || exitPrice.compareTo(BigDecimal.ZERO) == 0) {
                exitPrice = trade.getStopLoss(); // fallback to stored SL if avgPrice missing
            }

            String reason;
            boolean isStop = "STOP_MARKET".equalsIgnoreCase(orderType) || "STOP".equalsIgnoreCase(orderType)
                    || orderId.equals(trade.getStopLossOrderId());
            if (isStop) {
                reason = (trade.getOriginalStopLoss() != null
                        && trade.getStopLoss().compareTo(trade.getOriginalStopLoss()) != 0)
                        ? "TRAILING_STOP" : "STOP_LOSS";
            } else {
                reason = "TAKE_PROFIT";
            }

            logger.info("📡 WS Event: {} executed for Trade {} at price={}", reason, trade.getId(), exitPrice);
            closeTradeFromEvent(trade, exitPrice, reason);
        } catch (Exception e) {
            logger.error("Error handling WS order update: {}", e.getMessage(), e);
        }
    }

    private void closeTradeFromEvent(Trade trade, BigDecimal exitPrice, String reason) {
        try {
            String tradeSymbol = trade.getSymbol() != null ? trade.getSymbol() : symbol;
            String[] botCreds = resolveBotCredentials(trade.getUserId(), tradeSymbol);

            // Cancel remaining conditional orders
            if (trade.getStopLossOrderId() != null && !trade.getStopLossOrderId().equals(trade.getBinanceOrderId())) {
                if (botCreds != null) {
                    binanceClient.cancelOrderForBot(trade.getStopLossOrderId(), botCreds[0], botCreds[1], tradeSymbol);
                } else {
                    binanceClient.cancelOrder(trade.getStopLossOrderId());
                }
            }
            if (trade.getTakeProfitOrderId() != null && !trade.getTakeProfitOrderId().equals(trade.getBinanceOrderId())) {
                if (botCreds != null) {
                    binanceClient.cancelOrderForBot(trade.getTakeProfitOrderId(), botCreds[0], botCreds[1], tradeSymbol);
                } else {
                    binanceClient.cancelOrder(trade.getTakeProfitOrderId());
                }
            }

            // Commission = round-trip taker fee on notional (0.05% entry + 0.05% exit = 0.10%)
            BigDecimal commission = trade.getQuantity()
                    .multiply(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(0.001))
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
            String tradeSymbol = trade.getSymbol() != null ? trade.getSymbol() : symbol;
            String[] botCreds = resolveBotCredentials(trade.getUserId(), tradeSymbol);

            // Cancel remaining conditional orders first (so Binance doesn't fire SL/TP after manual close)
            if (trade.getStopLossOrderId() != null && !trade.getStopLossOrderId().equals(trade.getBinanceOrderId())) {
                if (botCreds != null) {
                    binanceClient.cancelOrderForBot(trade.getStopLossOrderId(), botCreds[0], botCreds[1], tradeSymbol);
                } else {
                    binanceClient.cancelOrder(trade.getStopLossOrderId());
                }
            }
            if (trade.getTakeProfitOrderId() != null && !trade.getTakeProfitOrderId().equals(trade.getBinanceOrderId())) {
                if (botCreds != null) {
                    binanceClient.cancelOrderForBot(trade.getTakeProfitOrderId(), botCreds[0], botCreds[1], tradeSymbol);
                } else {
                    binanceClient.cancelOrder(trade.getTakeProfitOrderId());
                }
            }

            String orderId;
            if ("SHORT".equals(trade.getAction())) {
                orderId = (botCreds != null)
                        ? binanceClient.placeShortBuyOrderForBot(trade.getQuantity(), botCreds[0], botCreds[1], tradeSymbol)
                        : binanceClient.placeShortBuyOrderForSymbol(tradeSymbol, trade.getQuantity());
            } else {
                orderId = (botCreds != null)
                        ? binanceClient.placeSellOrderForBot(trade.getQuantity(), botCreds[0], botCreds[1], tradeSymbol)
                        : binanceClient.placeSellOrderForSymbol(tradeSymbol, trade.getQuantity());
            }

            if (orderId == null) {
                // Binance close failed — position likely already closed by algo order (SL/TP triggered on Binance)
                // Force-close in DB to prevent trade stuck in OPEN state forever
                logger.warn("⚠️ Could not place close order for Trade {} on Binance (position may already be closed by algo order). Force-closing in DB at price {}.",
                        trade.getId(), exitPrice);
            }

            // Close in DB regardless: if orderId is null, algo order already closed the position on Binance
            BigDecimal commission = trade.getQuantity()
                    .multiply(trade.getEntryPrice())
                    .multiply(BigDecimal.valueOf(0.001))
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
