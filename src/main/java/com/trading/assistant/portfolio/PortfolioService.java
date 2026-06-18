package com.trading.assistant.portfolio;

import com.trading.assistant.binance.BinanceClient;
import com.trading.assistant.portfolio.model.DailyMetrics;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.DailyMetricsRepository;
import com.trading.assistant.portfolio.repository.TradeRepository;
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

    /**
     * Calculate daily metrics and save
     */
    @Scheduled(cron = "0 0 0 * * *") // Every day at midnight
    public void calculateDailyMetrics() {
        try {
            LocalDate today = LocalDate.now();
            
            DailyMetrics metrics = new DailyMetrics(today);
            
            // Get counts
            Long totalTrades = tradeRepository.count();
            Long winningTrades = tradeRepository.countWinningTrades();
            Long losingTrades = tradeRepository.countLosingTrades();
            
            metrics.setTotalTrades(totalTrades != null ? totalTrades.intValue() : 0);
            metrics.setWinningTrades(winningTrades != null ? winningTrades.intValue() : 0);
            metrics.setLosingTrades(losingTrades != null ? losingTrades.intValue() : 0);
            
            // Get P&L
            BigDecimal totalPnl = tradeRepository.calculateTotalPnl();
            metrics.setTotalPnl(totalPnl != null ? totalPnl : BigDecimal.ZERO);
            
            // Calculate derived metrics
            metrics.calculateMetrics();
            
            // Calculate profit factor
            BigDecimal grossProfit = tradeRepository.calculateGrossProfit();
            BigDecimal grossLoss = tradeRepository.calculateGrossLoss();
            
            if (grossLoss != null && grossLoss.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pf = grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP);
                metrics.setProfitFactor(pf);
            } else {
                metrics.setProfitFactor(grossProfit.compareTo(BigDecimal.ZERO) > 0 ? 
                        new BigDecimal("999.99") : BigDecimal.ZERO);
            }
            
            // Calculate max drawdown from cumulative equity curve
            BigDecimal maxDrawdown = calculateMaxDrawdown(null);
            metrics.setMaxDrawdown(maxDrawdown);

            dailyMetricsRepository.save(metrics);
            logger.info("Daily metrics calculated and saved for {}", today);
            
        } catch (Exception e) {
            logger.error("Error calculating daily metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Get portfolio summary (global or by symbol)
     */
    public Map<String, Object> getPortfolioSummary(String symbol) {
        Map<String, Object> summary = new HashMap<>();

        // Balance (global)
        BigDecimal balance = binanceClient.getBalance("USDT");
        summary.put("balance", balance);

        // Trades stats
        Long totalTrades, winningTrades, losingTrades, openTrades;
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
        BigDecimal totalPnl = symbol != null && !symbol.isEmpty()
                ? tradeRepository.calculateTotalPnlBySymbol(symbol)
                : tradeRepository.calculateTotalPnl();
        summary.put("totalPnl", totalPnl != null ? totalPnl : BigDecimal.ZERO);

        // Profit Factor
        BigDecimal grossProfit = symbol != null && !symbol.isEmpty()
                ? tradeRepository.calculateGrossProfitBySymbol(symbol)
                : tradeRepository.calculateGrossProfit();
        BigDecimal grossLoss = symbol != null && !symbol.isEmpty()
                ? tradeRepository.calculateGrossLossBySymbol(symbol)
                : tradeRepository.calculateGrossLoss();

        if (grossLoss != null && grossLoss.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal pf = grossProfit.divide(grossLoss, 4, RoundingMode.HALF_UP);
            summary.put("profitFactor", pf);
        } else {
            summary.put("profitFactor", grossProfit != null && grossProfit.compareTo(BigDecimal.ZERO) > 0 ?
                    new BigDecimal("999.99") : BigDecimal.ZERO);
        }

        // Max drawdown
        summary.put("maxDrawdown", calculateMaxDrawdown(symbol));

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
    private BigDecimal calculateMaxDrawdown(String symbol) {
        try {
            List<Trade> trades = tradeRepository.findClosedTradesOrderByExitTimeAsc();
            if (trades == null || trades.isEmpty()) return BigDecimal.ZERO;

            BigDecimal cumPnl = BigDecimal.ZERO;
            BigDecimal peak = BigDecimal.ZERO;
            BigDecimal maxDrawdown = BigDecimal.ZERO;

            for (Trade trade : trades) {
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
    public DailyMetrics getLatestMetrics(String symbol) {
        if (symbol != null && !symbol.isEmpty()) {
            return dailyMetricsRepository.findTopBySymbolOrderByDateDesc(symbol).orElse(null);
        }
        return dailyMetricsRepository.findTopByOrderByDateDesc().orElse(null);
    }

    /**
     * Get all daily metrics ordered by date ascending (for calendar/history view)
     */
    public List<DailyMetrics> getAllMetricsHistory(String symbol) {
        if (symbol != null && !symbol.isEmpty()) {
            return dailyMetricsRepository.findBySymbolOrderByDateAsc(symbol);
        }
        return dailyMetricsRepository.findAllByOrderByDateAsc();
    }
}
