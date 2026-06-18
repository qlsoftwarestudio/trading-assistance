package com.trading.assistant.portfolio;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.portfolio.model.DailyMetrics;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.DailyMetricsRepository;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.user.model.User;
import com.trading.assistant.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PortfolioService {

    private static final Logger logger = LoggerFactory.getLogger(PortfolioService.class);

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private DailyMetricsRepository dailyMetricsRepository;

    @Autowired
    private BinanceClient binanceClient;

    @Autowired
    private UserRepository userRepository;

    /**
     * Calculate daily metrics per user and save. Runs at midnight every day.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void calculateDailyMetrics() {
        List<User> users = userRepository.findAll();
        if (users.isEmpty()) {
            logger.warn("No users found — skipping daily metrics calculation");
            return;
        }
        logger.info("Calculating daily metrics for {} users", users.size());
        for (User user : users) {
            try {
                calculateDailyMetricsForUser(user.getId());
            } catch (Exception e) {
                logger.error("Error calculating daily metrics for userId={}: {}", user.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Calculate and persist today's snapshot metrics for a specific user.
     */
    public void calculateDailyMetricsForUser(Long userId) {
        LocalDate today = LocalDate.now();

        DailyMetrics metrics = new DailyMetrics(today);
        metrics.setUserId(userId);

        Long totalTrades = tradeRepository.countByUserIdAndStatus(userId, "CLOSED");
        Long winningTrades = tradeRepository.countWinningTradesByUserId(userId);
        Long losingTrades = tradeRepository.countLosingTradesByUserId(userId);

        metrics.setTotalTrades(totalTrades != null ? totalTrades.intValue() : 0);
        metrics.setWinningTrades(winningTrades != null ? winningTrades.intValue() : 0);
        metrics.setLosingTrades(losingTrades != null ? losingTrades.intValue() : 0);

        BigDecimal totalPnl = tradeRepository.calculateTotalPnlByUserId(userId);
        metrics.setTotalPnl(totalPnl != null ? totalPnl : BigDecimal.ZERO);

        metrics.calculateMetrics();

        BigDecimal grossProfit = tradeRepository.calculateGrossProfitByUserId(userId);
        BigDecimal grossLoss = tradeRepository.calculateGrossLossByUserId(userId);

        if (grossLoss != null && grossLoss.compareTo(BigDecimal.ZERO) > 0) {
            metrics.setProfitFactor(grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP));
        } else {
            metrics.setProfitFactor(grossProfit != null && grossProfit.compareTo(BigDecimal.ZERO) > 0
                    ? new BigDecimal("999.99") : BigDecimal.ZERO);
        }

        BigDecimal maxDrawdown = calculateMaxDrawdown(null, userId);
        metrics.setMaxDrawdown(maxDrawdown);

        dailyMetricsRepository.save(metrics);
        logger.info("Daily metrics saved for userId={} date={}: trades={}, pnl={}",
                userId, today, metrics.getTotalTrades(), metrics.getTotalPnl());
    }

    /**
     * Get portfolio summary (global or by symbol)
     */
    public Map<String, Object> getPortfolioSummary(String symbol, Long userId) {
        Map<String, Object> summary = new HashMap<>();

        // Balance (global)
        BigDecimal balance = binanceClient.getBalance("USDT");
        summary.put("balance", balance);

        // Trades stats
        Long totalTrades, winningTrades, losingTrades, openTrades;
        if (userId != null) {
            if (symbol != null && !symbol.isEmpty()) {
                totalTrades = tradeRepository.countByUserIdAndSymbolAndStatus(userId, symbol, "CLOSED");
                winningTrades = tradeRepository.countWinningTradesByUserId(userId);
                losingTrades = tradeRepository.countLosingTradesByUserId(userId);
                openTrades = tradeRepository.countByUserIdAndSymbolAndStatus(userId, symbol, "OPEN");
            } else {
                totalTrades = tradeRepository.countByUserIdAndStatus(userId, "CLOSED");
                winningTrades = tradeRepository.countWinningTradesByUserId(userId);
                losingTrades = tradeRepository.countLosingTradesByUserId(userId);
                openTrades = tradeRepository.countByUserIdAndStatus(userId, "OPEN");
            }
        } else {
            if (symbol != null && !symbol.isEmpty()) {
                totalTrades = tradeRepository.countBySymbolAndStatus(symbol, "CLOSED");
                winningTrades = tradeRepository.countWinningTradesBySymbol(symbol);
                losingTrades = tradeRepository.countLosingTradesBySymbol(symbol);
                openTrades = tradeRepository.countBySymbolAndStatus(symbol, "OPEN");
            } else {
                totalTrades = tradeRepository.count();
                winningTrades = tradeRepository.countWinningTrades();
                losingTrades = tradeRepository.countLosingTrades();
                openTrades = tradeRepository.countByStatus("OPEN");
            }
        }

        summary.put("totalTrades", totalTrades != null ? totalTrades : 0L);
        summary.put("winningTrades", winningTrades != null ? winningTrades : 0L);
        summary.put("losingTrades", losingTrades != null ? losingTrades : 0L);
        summary.put("openTrades", openTrades);

        // Win rate
        if (totalTrades != null && totalTrades > 0) {
            BigDecimal winRate = BigDecimal.valueOf(winningTrades != null ? winningTrades : 0L)
                    .divide(BigDecimal.valueOf(totalTrades), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            summary.put("winRate", winRate);
        } else {
            summary.put("winRate", BigDecimal.ZERO);
        }

        // P&L
        BigDecimal totalPnl;
        if (userId != null) {
            totalPnl = symbol != null && !symbol.isEmpty()
                    ? tradeRepository.calculateTotalPnlBySymbolAndUserId(symbol, userId)
                    : tradeRepository.calculateTotalPnlByUserId(userId);
        } else {
            totalPnl = symbol != null && !symbol.isEmpty()
                    ? tradeRepository.calculateTotalPnlBySymbol(symbol)
                    : tradeRepository.calculateTotalPnl();
        }
        summary.put("totalPnl", totalPnl != null ? totalPnl : BigDecimal.ZERO);

        // Profit Factor
        BigDecimal grossProfit, grossLoss;
        if (userId != null) {
            grossProfit = symbol != null && !symbol.isEmpty()
                    ? tradeRepository.calculateGrossProfitBySymbol(symbol)
                    : tradeRepository.calculateGrossProfitByUserId(userId);
            grossLoss = symbol != null && !symbol.isEmpty()
                    ? tradeRepository.calculateGrossLossBySymbol(symbol)
                    : tradeRepository.calculateGrossLossByUserId(userId);
        } else {
            grossProfit = symbol != null && !symbol.isEmpty()
                    ? tradeRepository.calculateGrossProfitBySymbol(symbol)
                    : tradeRepository.calculateGrossProfit();
            grossLoss = symbol != null && !symbol.isEmpty()
                    ? tradeRepository.calculateGrossLossBySymbol(symbol)
                    : tradeRepository.calculateGrossLoss();
        }

        if (grossLoss != null && grossLoss.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pf = grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP);
            summary.put("profitFactor", pf);
        } else {
            summary.put("profitFactor", grossProfit != null && grossProfit.compareTo(BigDecimal.ZERO) > 0 ?
                    new BigDecimal("999.99") : BigDecimal.ZERO);
        }

        // Max drawdown
        summary.put("maxDrawdown", calculateMaxDrawdown(symbol, userId));

        // Current price
        BigDecimal currentPrice = symbol != null && !symbol.isEmpty()
                ? binanceClient.getPrice(symbol)
                : binanceClient.getCurrentPrice();
        summary.put("currentPrice", currentPrice);

        return summary;
    }

    /**
     * Calculate max drawdown from cumulative PnL curve (peak-to-trough in $).
     */
    private BigDecimal calculateMaxDrawdown(String symbol, Long userId) {
        try {
            List<Trade> trades = tradeRepository.findClosedTradesOrderByExitTimeAsc();
            if (trades == null || trades.isEmpty()) return BigDecimal.ZERO;

            BigDecimal cumPnl = BigDecimal.ZERO;
            BigDecimal peak = BigDecimal.ZERO;
            BigDecimal maxDrawdown = BigDecimal.ZERO;

            for (Trade trade : trades) {
                if (userId != null && !userId.equals(trade.getUserId())) continue;
                if (symbol != null && !symbol.isEmpty() && !symbol.equals(trade.getSymbol())) continue;
                cumPnl = cumPnl.add(trade.getPnl());
                if (cumPnl.compareTo(peak) > 0) {
                    peak = cumPnl;
                }
                BigDecimal drawdown = peak.subtract(cumPnl);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
            return maxDrawdown.setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            logger.error("Error calculating max drawdown: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get latest daily metrics
     */
    public DailyMetrics getLatestMetrics(String symbol, Long userId) {
        if (userId != null) {
            if (symbol != null && !symbol.isEmpty()) {
                return dailyMetricsRepository.findTopByUserIdAndSymbolOrderByDateDesc(userId, symbol).orElse(null);
            }
            return dailyMetricsRepository.findTopByUserIdOrderByDateDesc(userId).orElse(null);
        }
        if (symbol != null && !symbol.isEmpty()) {
            return dailyMetricsRepository.findTopBySymbolOrderByDateDesc(symbol).orElse(null);
        }
        return dailyMetricsRepository.findTopByOrderByDateDesc().orElse(null);
    }

    /**
     * Get all daily metrics ordered by date ascending (for calendar/history view)
     */
    public List<DailyMetrics> getAllMetricsHistory(String symbol, Long userId) {
        if (userId != null) {
            if (symbol != null && !symbol.isEmpty()) {
                return dailyMetricsRepository.findByUserIdAndSymbolOrderByDateAsc(userId, symbol);
            }
            return dailyMetricsRepository.findByUserIdOrderByDateAsc(userId);
        }
        if (symbol != null && !symbol.isEmpty()) {
            return dailyMetricsRepository.findBySymbolOrderByDateAsc(symbol);
        }
        return dailyMetricsRepository.findAllByOrderByDateAsc();
    }
}
