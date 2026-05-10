package com.trading.assistant.strategy.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "signals")
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String action; // LONG or HOLD

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal price;

    @Column(precision = 5, scale = 2)
    private BigDecimal rsi;

    @Column(precision = 20, scale = 8)
    private BigDecimal sessionLow;

    @Column(precision = 10, scale = 4)
    private BigDecimal momentum;

    private Boolean inBuyZone;

    @Column(nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    private Boolean executed;

    @Column(name = "trade_id")
    private Long tradeId;

    @PrePersist
    protected void onCreate() {
        generatedAt = LocalDateTime.now();
        executed = false;
    }

    // Constructors
    public Signal() {}

    public Signal(String symbol, String action, BigDecimal price, BigDecimal rsi, 
                  BigDecimal sessionLow, BigDecimal momentum, Boolean inBuyZone) {
        this.symbol = symbol;
        this.action = action;
        this.price = price;
        this.rsi = rsi;
        this.sessionLow = sessionLow;
        this.momentum = momentum;
        this.inBuyZone = inBuyZone;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getRsi() { return rsi; }
    public void setRsi(BigDecimal rsi) { this.rsi = rsi; }

    public BigDecimal getSessionLow() { return sessionLow; }
    public void setSessionLow(BigDecimal sessionLow) { this.sessionLow = sessionLow; }

    public BigDecimal getMomentum() { return momentum; }
    public void setMomentum(BigDecimal momentum) { this.momentum = momentum; }

    public Boolean getInBuyZone() { return inBuyZone; }
    public void setInBuyZone(Boolean inBuyZone) { this.inBuyZone = inBuyZone; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }

    public Boolean getExecuted() { return executed; }
    public void setExecuted(Boolean executed) { this.executed = executed; }

    public Long getTradeId() { return tradeId; }
    public void setTradeId(Long tradeId) { this.tradeId = tradeId; }

    // Helper methods
    public boolean isLongSignal() {
        return "LONG".equals(this.action);
    }

    @Override
    public String toString() {
        return String.format("Signal{id=%d, symbol='%s', action='%s', price=%s, rsi=%s, generatedAt=%s}",
                id, symbol, action, price, rsi, generatedAt);
    }
}
