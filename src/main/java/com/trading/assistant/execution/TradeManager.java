package com.trading.assistant.execution;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.notification.TelegramBot;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Component
public class TradeManager {

    private static final Logger logger = LoggerFactory.getLogger(TradeManager.class);

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private TelegramBot telegramBot;

    @Value("${trading.strategy.stop-loss-pct:2.0}")
    private double stopLossPct;

    @Value("${trading.strategy.take-profit-pct:8.0}")
    private double takeProfitPct;

    @Value("${trading.strategy.position-size-pct:20.0}")
    private double positionSizePct;

    @Value("${trading.strategy.symbol:BTCUSDT}")
    private String symbol;

    /**
     * Check if there's an open position
     */
    public boolean hasOpenPosition() {
        long openTrades = tradeRepository.countByStatus("OPEN");
        return openTrades > 0;
    }

    /**
     * Execute LONG entry based on signal
     */
    public void executeLongEntry(Signal signal) {
        try {
            BigDecimal currentPrice = signal.getPrice();
            BigDecimal balance = binanceClient.getBalance("USDT");

            // Calculate position size (20% of balance)
            BigDecimal positionSize = balance
                    .multiply(BigDecimal.valueOf(positionSizePct))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

            // Calculate quantity
            BigDecimal quantity = positionSize.divide(currentPrice, 8, RoundingMode.HALF_DOWN);

            // Calculate stop loss and take profit
            BigDecimal stopLoss = currentPrice
                    .multiply(BigDecimal.valueOf(1 - stopLossPct / 100))
                    .setScale(8, RoundingMode.HALF_UP);

            BigDecimal takeProfit = currentPrice
                    .multiply(BigDecimal.valueOf(1 + takeProfitPct / 100))
                    .setScale(8, RoundingMode.HALF_UP);

            logger.info("Executing LONG entry - Price: {}, Quantity: {}, SL: {}, TP: {}",
                    currentPrice, quantity, stopLoss, takeProfit);

            // Place order on Binance
            String orderId = binanceClient.placeBuyOrder(quantity);

            if (orderId != null) {
                // Save trade to database
                Trade trade = new Trade(
                        symbol,
                        "LONG",
                        currentPrice,
                        quantity,
                        positionSize,
                        stopLoss,
                        takeProfit
                );
                trade.setBinanceOrderId(orderId);
                tradeRepository.save(trade);

                // Update signal with trade reference
                signal.setExecuted(true);
                signal.setTradeId(trade.getId());

                // Send notification
                telegramBot.sendTradeNotification(trade, "ENTRY");

                logger.info("✅ LONG trade executed successfully. Trade ID: {}, Order ID: {}",
                        trade.getId(), orderId);
            } else {
                logger.error("❌ Failed to execute LONG order on Binance");
            }

        } catch (Exception e) {
            logger.error("Error executing LONG entry: {}", e.getMessage(), e);
        }
    }

    /**
     * Monitor open trades and close if SL or TP hit
     */
    public void monitorAndCloseTrades() {
        try {
            List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
            BigDecimal currentPrice = binanceClient.getCurrentPrice();

            for (Trade trade : openTrades) {
                checkAndCloseTrade(trade, currentPrice);
            }

        } catch (Exception e) {
            logger.error("Error monitoring trades: {}", e.getMessage(), e);
        }
    }

    private void checkAndCloseTrade(Trade trade, BigDecimal currentPrice) {
        BigDecimal entryPrice = trade.getEntryPrice();
        BigDecimal stopLoss = trade.getStopLoss();
        BigDecimal takeProfit = trade.getTakeProfit();

        // Check if stop loss hit
        if (currentPrice.compareTo(stopLoss) <= 0) {
            logger.info("🛑 Stop Loss hit for Trade {}. Current: {}, SL: {}",
                    trade.getId(), currentPrice, stopLoss);
            closeTrade(trade, currentPrice, "STOP_LOSS");
            return;
        }

        // Check if take profit hit
        if (currentPrice.compareTo(takeProfit) >= 0) {
            logger.info("🎯 Take Profit hit for Trade {}. Current: {}, TP: {}",
                    trade.getId(), currentPrice, takeProfit);
            closeTrade(trade, currentPrice, "TAKE_PROFIT");
            return;
        }

        // Log monitoring every 15 minutes
        BigDecimal pnl = currentPrice.subtract(entryPrice).multiply(trade.getQuantity());
        logger.debug("Monitoring Trade {} - Current P&L: ${}", trade.getId(), pnl);
    }

    private void closeTrade(Trade trade, BigDecimal exitPrice, String reason) {
        try {
            // Place sell order on Binance
            String orderId = binanceClient.placeSellOrder(trade.getQuantity());

            if (orderId != null) {
                // Calculate commission (0.06% per trade = 0.12% round trip)
                BigDecimal commission = trade.getInvestedAmount()
                        .multiply(BigDecimal.valueOf(0.0012))
                        .setScale(8, RoundingMode.HALF_UP);

                // Close trade
                trade.close(exitPrice, reason, commission);
                tradeRepository.save(trade);

                // Send notification
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
