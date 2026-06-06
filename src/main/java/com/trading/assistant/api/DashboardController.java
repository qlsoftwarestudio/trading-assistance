package com.trading.assistant.api;

import com.trading.assistant.portfolio.PortfolioService;
import com.trading.assistant.portfolio.model.DailyMetrics;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.HypeStrategy;
import com.trading.assistant.strategy.backtest.BacktestResult;
import com.trading.assistant.strategy.backtest.BacktestService;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Trading Assistant API", description = "Dashboard and trading operations")
public class DashboardController {

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private HypeStrategy hypeStrategy;

    @Autowired
    private BacktestService backtestService;

    /**
     * Health check
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if service is running")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "trading-assistant");
        response.put("version", "1.0.0");
        return ResponseEntity.ok(response);
    }

    /**
     * Get portfolio summary
     */
    @GetMapping("/dashboard/summary")
    @Operation(summary = "Get portfolio summary", description = "Returns balance, P&L, win rate, and other metrics")
    public ResponseEntity<Map<String, Object>> getDashboardSummary() {
        Map<String, Object> summary = portfolioService.getPortfolioSummary();
        return ResponseEntity.ok(summary);
    }

    /**
     * Get all trades (paginated)
     */
    @GetMapping("/dashboard/trades")
    @Operation(summary = "Get trades", description = "Returns paginated list of all trades")
    public ResponseEntity<Page<Trade>> getTrades(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        
        PageRequest pageRequest = PageRequest.of(page, size, 
                Sort.by(Sort.Direction.DESC, "entryTime"));
        
        Page<Trade> trades = tradeRepository.findAllByOrderByEntryTimeDesc(pageRequest);
        return ResponseEntity.ok(trades);
    }

    /**
     * Get open trades
     */
    @GetMapping("/dashboard/trades/open")
    @Operation(summary = "Get open trades", description = "Returns list of currently open trades")
    public ResponseEntity<List<Trade>> getOpenTrades() {
        List<Trade> openTrades = tradeRepository.findByStatusOrderByEntryTimeDesc("OPEN");
        return ResponseEntity.ok(openTrades);
    }

    /**
     * Get recent signals
     */
    @GetMapping("/dashboard/signals")
    @Operation(summary = "Get recent signals", description = "Returns last 50 generated signals")
    public ResponseEntity<List<Signal>> getRecentSignals() {
        List<Signal> signals = signalRepository.findTop50ByOrderByGeneratedAtDesc();
        return ResponseEntity.ok(signals);
    }

    /**
     * Get latest daily metrics
     */
    @GetMapping("/dashboard/metrics")
    @Operation(summary = "Get daily metrics", description = "Returns latest calculated daily metrics")
    public ResponseEntity<DailyMetrics> getDailyMetrics() {
        DailyMetrics metrics = portfolioService.getLatestMetrics();
        if (metrics != null) {
            return ResponseEntity.ok(metrics);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get strategy status
     */
    @GetMapping("/strategy/status")
    @Operation(summary = "Get strategy status", description = "Returns current strategy configuration and status")
    public ResponseEntity<Map<String, String>> getStrategyStatus() {
        Map<String, String> status = new HashMap<>();
        status.put("strategy", hypeStrategy.getStrategyStatus());
        status.put("status", "ACTIVE");
        return ResponseEntity.ok(status);
    }

    /**
     * Manual trigger for strategy (for testing)
     */
    @PostMapping("/strategy/execute")
    @Operation(summary = "Execute strategy manually", description = "Manually trigger strategy execution (for testing)")
    public ResponseEntity<Map<String, String>> executeStrategy() {
        hypeStrategy.executeStrategyManual();
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Strategy executed manually with real kline data");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Monitor trades manually (for testing)
     */
    @PostMapping("/trades/monitor")
    @Operation(summary = "Monitor trades", description = "Manually trigger trade monitoring (for testing)")
    public ResponseEntity<Map<String, String>> monitorTrades() {
        hypeStrategy.monitorOpenTrades();

        Map<String, String> response = new HashMap<>();
        response.put("message", "Trade monitoring executed");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Run backtest with current strategy parameters
     */
    @PostMapping("/backtest")
    @Operation(summary = "Run backtest", description = "Simulate strategy on historical klines")
    public ResponseEntity<?> runBacktest(@RequestParam(defaultValue = "500") int limit) {
        BacktestResult result = backtestService.runBacktest(limit);
        if (result == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Not enough data for backtest");
            return ResponseEntity.badRequest().body(error);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Run walk-forward backtest
     */
    @PostMapping("/backtest/walk-forward")
    @Operation(summary = "Run walk-forward backtest", description = "Train on 70% / test on 30% to detect overfitting")
    public ResponseEntity<?> runWalkForwardBacktest(@RequestParam(defaultValue = "500") int limit) {
        BacktestResult result = backtestService.runWalkForwardBacktest(limit);
        if (result == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Not enough data for walk-forward backtest");
            return ResponseEntity.badRequest().body(error);
        }
        return ResponseEntity.ok(result);
    }
}
