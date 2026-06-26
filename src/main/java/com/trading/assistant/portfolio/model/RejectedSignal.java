package com.trading.assistant.portfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "rejected_signals")
public class RejectedSignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String action; // LONG or SHORT

    @Column(nullable = false, length = 20)
    private String strategy; // HYPE or SCALP

    @Column(length = 50)
    private String setupType; // MEAN_REVERSION, TREND_DIP, SCALP_INDUCTION, etc.

    @Column(nullable = false, length = 100)
    private String rejectionReason; // e.g. RSI_NOT_OVERSOLD, VWAP_FILTER, TREND1H_UP

    @Column(precision = 20, scale = 8)
    private BigDecimal price;

    @Column(precision = 5, scale = 2)
    private BigDecimal rsi;

    @Column(precision = 10, scale = 4)
    private BigDecimal momentum;

    @Column(precision = 10, scale = 4)
    private BigDecimal vwapDistancePct;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public RejectedSignal() {}

    public RejectedSignal(String symbol, String action, String strategy, String setupType,
                          String rejectionReason, BigDecimal price, BigDecimal rsi,
                          BigDecimal momentum, BigDecimal vwapDistancePct) {
        this.symbol = symbol;
        this.action = action;
        this.strategy = strategy;
        this.setupType = setupType;
        this.rejectionReason = rejectionReason;
        this.price = price;
        this.rsi = rsi;
        this.momentum = momentum;
        this.vwapDistancePct = vwapDistancePct;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getRsi() { return rsi; }
    public void setRsi(BigDecimal rsi) { this.rsi = rsi; }

    public BigDecimal getMomentum() { return momentum; }
    public void setMomentum(BigDecimal momentum) { this.momentum = momentum; }

    public BigDecimal getVwapDistancePct() { return vwapDistancePct; }
    public void setVwapDistancePct(BigDecimal vwapDistancePct) { this.vwapDistancePct = vwapDistancePct; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return String.format("RejectedSignal{symbol='%s', action='%s', strategy='%s', reason='%s'}",
                symbol, action, strategy, rejectionReason);
    }
}
