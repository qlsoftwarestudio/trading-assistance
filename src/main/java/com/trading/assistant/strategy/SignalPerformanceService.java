package com.trading.assistant.strategy;

import com.trading.assistant.portfolio.model.Trade;
import com.trading.assistant.portfolio.repository.TradeRepository;
import com.trading.assistant.strategy.model.Signal;
import com.trading.assistant.strategy.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analiza el desempeno historico de las senales para identificar
 * que "patrones" de mercado tienen mejor win rate y profit factor.
 * Usa esta informacion para sugerir ajustes dinamicos de umbrales.
 */
@Service
public class SignalPerformanceService {

    private static final Logger logger = LoggerFactory.getLogger(SignalPerformanceService.class);

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Value("${trading.performance.min-samples:10}")
    private int minSamplesForAdjustment;

    @Value("${trading.performance.adjustment-delta-pct:5.0}")
    private double adjustmentDeltaPct;

    private final Map<String, PatternStats> patternCache = new HashMap<>();

    /**
     * Regenera estadisticas de patrones cada 6 horas.
     */
    @Scheduled(fixedRate = 21600000)
    public void refreshPatternStats() {
        try {
            List<Signal> executedSignals = signalRepository.findByExecutedTrue();
            Map<String, List<SignalResult>> resultsByPattern = new HashMap<>();

            for (Signal signal : executedSignals) {
                if (signal.getTradeId() == null) continue;
                Optional<Trade> tradeOpt = tradeRepository.findById(signal.getTradeId());
                if (tradeOpt.isEmpty() || !"CLOSED".equals(tradeOpt.get().getStatus())) {
                    continue;
                }
                Trade trade = tradeOpt.get();
                String patternKey = buildPatternKey(signal);
                resultsByPattern
                        .computeIfAbsent(patternKey, k -> new ArrayList<>())
                        .add(new SignalResult(signal, trade));
            }

            patternCache.clear();
            for (Map.Entry<String, List<SignalResult>> entry : resultsByPattern.entrySet()) {
                PatternStats stats = calculateStats(entry.getValue());
                patternCache.put(entry.getKey(), stats);
                logger.info("Pattern '{}' -> {} samples, winRate={}%, profitFactor={}, avgPnl={}",
                        entry.getKey(), stats.sampleSize,
                        String.format("%.1f", stats.winRate * 100),
                        String.format("%.2f", stats.profitFactor),
                        String.format("%.2f", stats.avgPnl));
            }

        } catch (Exception e) {
            logger.error("Error refreshing pattern stats: {}", e.getMessage(), e);
        }
    }

    /**
     * Evalua si una senal entrante tiene un patron con buen historial.
     * Retorna un score entre 0.0 (malo) y 1.0 (excelente).
     */
    public double scoreSignal(Signal signal) {
        String key = buildPatternKey(signal);
        PatternStats stats = patternCache.get(key);
        if (stats == null || stats.sampleSize < minSamplesForAdjustment) {
            return 0.5; // Neutral si no hay suficiente historial
        }
        // Score basado en win rate y profit factor
        double winRateScore = stats.winRate;
        double pfScore = Math.min(stats.profitFactor / 2.0, 1.0); // normalizar a 1.0
        return (winRateScore + pfScore) / 2.0;
    }

    /**
     * Obtiene el mejor umbral sugerido para RSI basado en patrones historicos.
     * Retorna null si no hay suficientes datos.
     */
    public ThresholdAdjustments suggestAdjustments() {
        if (patternCache.isEmpty()) {
            return null;
        }

        List<PatternStats> allPatterns = new ArrayList<>(patternCache.values());
        List<PatternStats> goodPatterns = allPatterns.stream()
                .filter(p -> p.sampleSize >= minSamplesForAdjustment && p.winRate >= 0.5 && p.profitFactor >= 1.0)
                .sorted(Comparator.comparingDouble((PatternStats p) -> p.profitFactor).reversed())
                .limit(5)
                .collect(Collectors.toList());

        if (goodPatterns.isEmpty()) {
            return null;
        }

        double avgRsi = goodPatterns.stream()
                .mapToDouble(p -> p.avgRsiAtEntry)
                .average()
                .orElse(30.0);
        double avgMomentum = goodPatterns.stream()
                .mapToDouble(p -> p.avgMomentumAtEntry)
                .average()
                .orElse(0.8);

        return new ThresholdAdjustments(avgRsi, avgMomentum);
    }

    private String buildPatternKey(Signal signal) {
        // Agrupa por tendencias macro + accion (LONG/SHORT)
        StringBuilder sb = new StringBuilder();
        sb.append(signal.getAction()).append("|");
        sb.append(safe(signal.getTrend1h())).append("|");
        sb.append(safe(signal.getTrend4h())).append("|");
        sb.append(safe(signal.getTrend1d())).append("|");
        sb.append(signal.getConfluence() != null && signal.getConfluence() ? "CONF" : "NOCONF").append("|");
        sb.append(signal.getRelativeVolume() != null && signal.getRelativeVolume().doubleValue() >= 1.0 ? "HIGHVOL" : "LOWVOL");
        return sb.toString();
    }

    private String safe(String s) {
        return s == null ? "UNK" : s;
    }

    private PatternStats calculateStats(List<SignalResult> results) {
        int wins = 0, losses = 0;
        double totalProfit = 0, totalLoss = 0;
        double sumRsi = 0, sumMomentum = 0;
        double sumPnl = 0;

        for (SignalResult sr : results) {
            BigDecimal pnl = sr.trade.getPnl();
            if (pnl == null) continue;
            sumPnl += pnl.doubleValue();
            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                wins++;
                totalProfit += pnl.doubleValue();
            } else {
                losses++;
                totalLoss += Math.abs(pnl.doubleValue());
            }
            if (sr.signal.getRsi() != null) sumRsi += sr.signal.getRsi().doubleValue();
            if (sr.signal.getMomentum() != null) sumMomentum += sr.signal.getMomentum().doubleValue();
        }

        PatternStats stats = new PatternStats();
        stats.sampleSize = results.size();
        stats.winRate = stats.sampleSize > 0 ? (double) wins / stats.sampleSize : 0.0;
        stats.profitFactor = totalLoss > 0 ? totalProfit / totalLoss : (totalProfit > 0 ? 999.99 : 0.0);
        stats.avgPnl = stats.sampleSize > 0 ? sumPnl / stats.sampleSize : 0.0;
        stats.avgRsiAtEntry = stats.sampleSize > 0 ? sumRsi / stats.sampleSize : 30.0;
        stats.avgMomentumAtEntry = stats.sampleSize > 0 ? sumMomentum / stats.sampleSize : 0.8;
        return stats;
    }

    private static class SignalResult {
        final Signal signal;
        final Trade trade;
        SignalResult(Signal signal, Trade trade) {
            this.signal = signal;
            this.trade = trade;
        }
    }

    private static class PatternStats {
        int sampleSize;
        double winRate;
        double profitFactor;
        double avgPnl;
        double avgRsiAtEntry;
        double avgMomentumAtEntry;
    }

    public static class ThresholdAdjustments {
        public final double suggestedRsiOversold;
        public final double suggestedMinMomentum;

        public ThresholdAdjustments(double suggestedRsiOversold, double suggestedMinMomentum) {
            this.suggestedRsiOversold = suggestedRsiOversold;
            this.suggestedMinMomentum = suggestedMinMomentum;
        }
    }
}
