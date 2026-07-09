package com.trading.assistant.api;

import com.trading.assistant.notification.TelegramBot;
import com.trading.assistant.portfolio.PortfolioService;
import com.trading.assistant.portfolio.model.DailyMetrics;
import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.RejectedSignalRepository;
import com.trading.assistant.portfolio.repository.TradeJournalRepository;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.AutoAdjustService;
import com.trading.assistant.strategy.HypeStrategy;
import com.trading.assistant.strategy.ScalpStrategy;
import com.trading.assistant.strategy.backtest.BacktestResult;
import com.trading.assistant.strategy.backtest.BacktestService;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import com.trading.assistant.user.service.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${trading.strategy.symbols:SOLUSDT}")
    private String configuredSymbols;

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private HypeStrategy hypeStrategy;

    @Autowired(required = false)
    private ScalpStrategy scalpStrategy;

    @Autowired
    private BacktestService backtestService;

    @Autowired
    private TelegramBot telegramBot;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RejectedSignalRepository rejectedSignalRepository;

    @Autowired
    private TradeJournalRepository tradeJournalRepository;

    @Autowired
    private AutoAdjustService autoAdjustService;

    private Long getUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) return null;
        return jwtUtil.getUserId(token);
    }

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
    @Operation(summary = "Get portfolio summary", description = "Returns balance, P&L, win rate, and other metrics. Optional ?symbol= filter.")
    public ResponseEntity<?> getDashboardSummary(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                  @RequestParam(required = false) String symbol) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }
        Map<String, Object> summary = portfolioService.getPortfolioSummary(symbol, userId);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get all trades (paginated)
     */
    @GetMapping("/dashboard/trades")
    @Operation(summary = "Get trades", description = "Returns paginated list of all trades. Optional ?symbol= filter.")
    public ResponseEntity<?> getTrades(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestParam(required = false) String symbol) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "entryTime"));

        Page<Trade> trades = symbol != null && !symbol.isEmpty()
                ? tradeRepository.findByUserIdAndSymbolOrderByEntryTimeDesc(userId, symbol, pageRequest)
                : tradeRepository.findByUserIdOrderByEntryTimeDesc(userId, pageRequest);
        return ResponseEntity.ok(trades);
    }

    /**
     * Get open trades
     */
    @GetMapping("/dashboard/trades/open")
    @Operation(summary = "Get open trades", description = "Returns list of currently open trades. Optional ?symbol= filter.")
    public ResponseEntity<?> getOpenTrades(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @RequestParam(required = false) String symbol) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        List<Trade> openTrades = symbol != null && !symbol.isEmpty()
                ? tradeRepository.findByUserIdAndSymbolAndStatusOrderByEntryTimeDesc(userId, symbol, "OPEN")
                : tradeRepository.findByUserIdAndStatusOrderByEntryTimeDesc(userId, "OPEN");
        return ResponseEntity.ok(openTrades);
    }

    /**
     * Get recent signals
     */
    @GetMapping("/dashboard/signals")
    @Operation(summary = "Get recent signals", description = "Returns last 50 generated signals. Optional ?symbol= filter.")
    public ResponseEntity<?> getRecentSignals(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                 @RequestParam(required = false) String symbol) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        List<Signal> signals = symbol != null && !symbol.isEmpty()
                ? signalRepository.findTop50ByUserIdAndSymbolOrderByGeneratedAtDesc(userId, symbol)
                : signalRepository.findTop50ByUserIdOrderByGeneratedAtDesc(userId);
        return ResponseEntity.ok(signals);
    }

    /**
     * Get all recent signals (accepted + rejected) unified
     */
    @GetMapping("/dashboard/signals/all")
    @Operation(summary = "Get all recent signals", description = "Returns last 50 accepted and rejected signals combined. Optional ?symbol= filter.")
    public ResponseEntity<?> getAllRecentSignals(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                  @RequestParam(required = false) String symbol,
                                                  @RequestParam(defaultValue = "24") int hours) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusHours(hours);

        List<Map<String, Object>> unified = new java.util.ArrayList<>();

        // Accepted signals
        List<Signal> accepted = symbol != null && !symbol.isEmpty()
                ? signalRepository.findTop50ByUserIdAndSymbolOrderByGeneratedAtDesc(userId, symbol)
                : signalRepository.findTop50ByUserIdOrderByGeneratedAtDesc(userId);
        for (Signal s : accepted) {
            if (s.getGeneratedAt() == null || s.getGeneratedAt().isBefore(since)) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("symbol", s.getSymbol());
            m.put("action", s.getAction());
            m.put("price", s.getPrice());
            m.put("rsi", s.getRsi());
            m.put("setupType", s.getSetupType());
            m.put("status", "ACCEPTED");
            m.put("timestamp", s.getGeneratedAt().toString());
            m.put("rejectionReason", null);
            m.put("executed", s.getExecuted());
            m.put("bbUpper", s.getBbUpper());
            m.put("bbLower", s.getBbLower());
            m.put("stochK5m", s.getStochK5m());
            m.put("stochD5m", s.getStochD5m());
            unified.add(m);
        }

        // Rejected signals
        List<com.trading.assistant.portfolio.model.RejectedSignal> rejected;
        if (symbol != null && !symbol.isEmpty()) {
            rejected = rejectedSignalRepository.findBySymbolAndCreatedAtAfterOrderByCreatedAtDesc(symbol, since);
        } else {
            rejected = rejectedSignalRepository.findAll().stream()
                    .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(since))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .limit(50)
                    .toList();
        }
        for (com.trading.assistant.portfolio.model.RejectedSignal r : rejected) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", "rej-" + r.getId());
            m.put("symbol", r.getSymbol());
            m.put("action", r.getAction());
            m.put("price", r.getPrice());
            m.put("rsi", r.getRsi());
            m.put("setupType", r.getSetupType());
            m.put("status", "REJECTED");
            m.put("timestamp", r.getCreatedAt().toString());
            m.put("rejectionReason", r.getRejectionReason());
            m.put("executed", false);
            m.put("bbUpper", null);
            m.put("bbLower", null);
            m.put("stochK5m", null);
            m.put("stochD5m", null);
            unified.add(m);
        }

        // Sort by timestamp desc
        unified.sort((a, b) -> {
            String ta = (String) a.get("timestamp");
            String tb = (String) b.get("timestamp");
            return tb.compareTo(ta);
        });

        if (unified.size() > 50) {
            unified = unified.subList(0, 50);
        }

        return ResponseEntity.ok(unified);
    }

    /**
     * Get latest daily metrics
     */
    @GetMapping("/dashboard/metrics")
    @Operation(summary = "Get daily metrics", description = "Returns latest calculated daily metrics. Optional ?symbol= filter.")
    public ResponseEntity<?> getDailyMetrics(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                               @RequestParam(required = false) String symbol) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        DailyMetrics metrics = portfolioService.getLatestMetrics(symbol, userId);
        if (metrics != null) {
            return ResponseEntity.ok(metrics);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all daily metrics history (for calendar/performance view)
     */
    @GetMapping("/dashboard/metrics/history")
    @Operation(summary = "Get metrics history", description = "Returns all daily metrics ordered by date ascending. Optional ?symbol= filter.")
    public ResponseEntity<?> getMetricsHistory(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                @RequestParam(required = false) String symbol) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        return ResponseEntity.ok(portfolioService.getAllMetricsHistory(symbol, userId));
    }

    /**
     * Get setup performance (hit rate per setup type) — Phase 3.2
     */
    @GetMapping("/dashboard/setup-performance")
    @Operation(summary = "Setup performance", description = "Win rate, avg PnL, and trade count per setup type")
    public ResponseEntity<?> getSetupPerformance(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                  @RequestParam(defaultValue = "7") int days) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(days);
        String[] setups = {"Mean-Reversion", "Breakout", "Trend-Dip",
                "SCALP_INDUCTION", "SCALP_DIVERGENCE", "SCALP_MEAN_REVERSION", "SCALP_VWAP_BOUNCE", "SCALP_VWAP_REJECTION"};
        String[] actions = {"LONG", "SHORT"};

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (String setup : setups) {
            for (String action : actions) {
                Long total = tradeJournalRepository.countTotalBySetupTypeAndAction(setup, action, since);
                if (total == null || total == 0) continue;
                Long wins = tradeJournalRepository.countWinsBySetupTypeAndAction(setup, action, since);
                java.math.BigDecimal avgPnl = tradeJournalRepository.avgPnlBySetupTypeAndAction(setup, action, since);
                double winRate = total > 0 ? (double) wins / total : 0;

                Map<String, Object> row = new HashMap<>();
                row.put("setup", setup);
                row.put("action", action);
                row.put("totalTrades", total);
                row.put("winningTrades", wins);
                row.put("losingTrades", total - wins);
                row.put("winRate", Math.round(winRate * 1000) / 10.0);
                row.put("avgPnl", avgPnl != null ? avgPnl.doubleValue() : 0);
                results.add(row);
            }
        }
        return ResponseEntity.ok(results);
    }

    /**
     * Get rejection heatmap — Phase 3.2
     */
    @GetMapping("/dashboard/rejection-heatmap")
    @Operation(summary = "Rejection heatmap", description = "Count of rejected signals by reason (last N days)")
    public ResponseEntity<?> getRejectionHeatmap(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                                  @RequestParam(defaultValue = "7") int days) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(days);
        List<Object[]> raw = rejectedSignalRepository.countByRejectionReasonSince(since);

        List<Map<String, Object>> results = new java.util.ArrayList<>();
        long total = 0;
        for (Object[] row : raw) {
            long count = ((Number) row[1]).longValue();
            total += count;
        }
        for (Object[] row : raw) {
            Map<String, Object> item = new HashMap<>();
            item.put("reason", row[0]);
            item.put("count", row[1]);
            item.put("pct", total > 0 ? Math.round(((Number) row[1]).doubleValue() / total * 1000) / 10.0 : 0);
            results.add(item);
        }
        return ResponseEntity.ok(results);
    }

    /**
     * Get symbol comparison (A/B testing) — Phase 3.3
     */
    @GetMapping("/dashboard/symbol-comparison")
    @Operation(summary = "Symbol comparison", description = "Compare performance metrics between symbols")
    public ResponseEntity<?> getSymbolComparison(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = getUserIdFromToken(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String[] symbols = configuredSymbols.split(",");
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (String sym : symbols) {
            Long total = tradeRepository.countByUserIdAndSymbolAndStatus(userId, sym, "CLOSED");
            Long wins = tradeRepository.countWinningTradesBySymbolAndUserId(sym, userId);
            Long losses = tradeRepository.countLosingTradesBySymbolAndUserId(sym, userId);
            java.math.BigDecimal totalPnl = tradeRepository.calculateTotalPnlBySymbolAndUserId(sym, userId);
            java.math.BigDecimal grossProfit = tradeRepository.calculateGrossProfitBySymbolAndUserId(sym, userId);
            java.math.BigDecimal grossLoss = tradeRepository.calculateGrossLossBySymbolAndUserId(sym, userId);

            double winRate = total != null && total > 0 ? (double) wins / total * 100 : 0;
            double profitFactor = grossLoss != null && grossLoss.compareTo(java.math.BigDecimal.ZERO) > 0
                    ? grossProfit.divide(grossLoss, 4, java.math.RoundingMode.HALF_UP).doubleValue()
                    : (grossProfit != null && grossProfit.compareTo(java.math.BigDecimal.ZERO) > 0 ? 999.99 : 0);

            Map<String, Object> row = new HashMap<>();
            row.put("symbol", sym);
            row.put("totalTrades", total != null ? total : 0);
            row.put("winningTrades", wins != null ? wins : 0);
            row.put("losingTrades", losses != null ? losses : 0);
            row.put("winRate", Math.round(winRate * 10) / 10.0);
            row.put("totalPnl", totalPnl != null ? totalPnl.doubleValue() : 0);
            row.put("profitFactor", Math.round(profitFactor * 100) / 100.0);
            row.put("grossProfit", grossProfit != null ? grossProfit.doubleValue() : 0);
            row.put("grossLoss", grossLoss != null ? grossLoss.doubleValue() : 0);
            results.add(row);
        }
        return ResponseEntity.ok(results);
    }

    /**
     * Get strategy status (both swing and hunter)
     */
    @GetMapping("/strategy/status")
    @Operation(summary = "Get strategy status", description = "Returns current strategy configuration and running status")
    public ResponseEntity<Map<String, Object>> getStrategyStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("swing", Map.of(
                "running", hypeStrategy.isRunning(),
                "description", hypeStrategy.getStrategyStatus()
        ));
        status.put("hunter", Map.of(
                "running", scalpStrategy != null ? scalpStrategy.isRunning() : false
        ));
        status.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(status);
    }

    /**
     * Toggle swing strategy on/off
     */
    @PostMapping("/strategy/toggle")
    @Operation(summary = "Toggle swing strategy", description = "Toggle swing (HypeStrategy) ON/OFF")
    public ResponseEntity<Map<String, Object>> toggleSwingStrategy() {
        boolean nowRunning = hypeStrategy.toggle();
        Map<String, Object> response = new HashMap<>();
        response.put("swingRunning", nowRunning);
        response.put("message", nowRunning ? "Swing strategy STARTED" : "Swing strategy STOPPED");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Toggle hunter/scalp strategy on/off
     */
    @PostMapping("/strategy/hunter/toggle")
    @Operation(summary = "Toggle hunter strategy", description = "Toggle hunter/scalp strategy ON/OFF")
    public ResponseEntity<Map<String, Object>> toggleHunterStrategy() {
        if (scalpStrategy == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("hunterRunning", false);
            response.put("message", "Hunter strategy is disabled via configuration");
            response.put("timestamp", java.time.LocalDateTime.now().toString());
            return ResponseEntity.ok(response);
        }
        boolean nowRunning = scalpStrategy.toggle();
        Map<String, Object> response = new HashMap<>();
        response.put("hunterRunning", nowRunning);
        response.put("message", nowRunning ? "Hunter strategy STARTED" : "Hunter strategy STOPPED");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
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

    /**
     * Test Telegram notification
     */
    @PostMapping("/telegram/test")
    @Operation(summary = "Test Telegram", description = "Send a test message to Telegram to verify configuration")
    public ResponseEntity<Map<String, String>> testTelegram() {
        telegramBot.sendAlert("Test", "✅ This is a test message from your Trading Assistant bot!");

        Map<String, String> response = new HashMap<>();
        response.put("message", "Test notification sent. Check your Telegram.");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }
}
