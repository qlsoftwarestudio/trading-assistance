package com.trading.assistant.portfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_metrics")
public class DailyMetrics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate date;

    private Integer totalTrades = 0;

    private Integer winningTrades = 0;

    private Integer losingTrades = 0;

    @Column(precision = 20, scale = 8)
    private BigDecimal totalPnl = BigDecimal.ZERO;

    @Column(precision = 5, scale = 2)
    private BigDecimal winRate;

    @Column(precision = 10, scale = 4)
    private BigDecimal profitFactor;

    @Column(precision = 10, scale = 4)
    private BigDecimal maxDrawdown;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (winRate == null) winRate = BigDecimal.ZERO;
        if (profitFactor == null) profitFactor = BigDecimal.ZERO;
        if (maxDrawdown == null) maxDrawdown = BigDecimal.ZERO;
    }

    // Constructors
    public DailyMetrics() {}

    public DailyMetrics(LocalDate date) {
        this.date = date;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Integer getTotalTrades() { return totalTrades; }
    public void setTotalTrades(Integer totalTrades) { this.totalTrades = totalTrades; }

    public Integer getWinningTrades() { return winningTrades; }
    public void setWinningTrades(Integer winningTrades) { this.winningTrades = winningTrades; }

    public Integer getLosingTrades() { return losingTrades; }
    public void setLosingTrades(Integer losingTrades) { this.losingTrades = losingTrades; }

    public BigDecimal getTotalPnl() { return totalPnl; }
    public void setTotalPnl(BigDecimal totalPnl) { this.totalPnl = totalPnl; }

    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }

    public BigDecimal getProfitFactor() { return profitFactor; }
    public void setProfitFactor(BigDecimal profitFactor) { this.profitFactor = profitFactor; }

    public BigDecimal getMaxDrawdown() { return maxDrawdown; }
    public void setMaxDrawdown(BigDecimal maxDrawdown) { this.maxDrawdown = maxDrawdown; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    // Helper method to calculate derived metrics
    public void calculateMetrics() {
        if (totalTrades != null && totalTrades > 0) {
            this.winRate = BigDecimal.valueOf(winningTrades)
                    .divide(BigDecimal.valueOf(totalTrades), 4, BigDecimal.ROUND_HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }

    @Override
    public String toString() {
        return String.format("DailyMetrics{date=%s, trades=%d, winRate=%s%%, pnl=%s}",
                date, totalTrades, winRate, totalPnl);
    }
}
