package com.trading.assistant.portfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 10)
    private String action; // LONG only for SOLO LONG strategy

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal exitPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal investedAmount;

    @Column(nullable = false)
    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    @Column(precision = 20, scale = 8)
    private BigDecimal stopLoss;

    @Column(precision = 20, scale = 8)
    private BigDecimal originalStopLoss;

    @Column(precision = 20, scale = 8)
    private BigDecimal takeProfit;

    @Column(name = "tp1_price", precision = 20, scale = 8)
    private BigDecimal tp1Price;

    @Column(name = "partial_closed")
    private Boolean partialClosed;

    @Column(precision = 20, scale = 8)
    private BigDecimal pnl;

    @Column(precision = 10, scale = 4)
    private BigDecimal pnlPercent;

    @Column(nullable = false, length = 20)
    private String status; // OPEN, CLOSED

    @Column(length = 20)
    private String exitReason; // STOP_LOSS, TAKE_PROFIT, MANUAL

    @Column(length = 50)
    private String setupType; // MEAN_REVERSION, BREAKOUT, TREND_DIP, SCALP_*

    @Column(precision = 20, scale = 8)
    private BigDecimal commission;

    @Column(length = 100)
    private String binanceOrderId;

    @Column(length = 100)
    private String stopLossOrderId;

    @Column(length = 100)
    private String takeProfitOrderId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors
    public Trade() {}

    public Trade(String symbol, String action, BigDecimal entryPrice, BigDecimal quantity, 
                 BigDecimal investedAmount, BigDecimal stopLoss, BigDecimal takeProfit) {
        this.symbol = symbol;
        this.action = action;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.investedAmount = investedAmount;
        this.stopLoss = stopLoss;
        this.originalStopLoss = stopLoss;
        this.takeProfit = takeProfit;
        this.entryTime = LocalDateTime.now();
        this.status = "OPEN";
        this.commission = BigDecimal.ZERO;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getInvestedAmount() { return investedAmount; }
    public void setInvestedAmount(BigDecimal investedAmount) { this.investedAmount = investedAmount; }

    public LocalDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalDateTime entryTime) { this.entryTime = entryTime; }

    public LocalDateTime getExitTime() { return exitTime; }
    public void setExitTime(LocalDateTime exitTime) { this.exitTime = exitTime; }

    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }

    public BigDecimal getOriginalStopLoss() { return originalStopLoss; }
    public void setOriginalStopLoss(BigDecimal originalStopLoss) { this.originalStopLoss = originalStopLoss; }

    public BigDecimal getTakeProfit() { return takeProfit; }
    public void setTakeProfit(BigDecimal takeProfit) { this.takeProfit = takeProfit; }

    public BigDecimal getTp1Price() { return tp1Price; }
    public void setTp1Price(BigDecimal tp1Price) { this.tp1Price = tp1Price; }

    public boolean isPartialClosed() { return Boolean.TRUE.equals(partialClosed); }
    public void setPartialClosed(Boolean partialClosed) { this.partialClosed = partialClosed; }

    public BigDecimal getPnl() { return pnl; }
    public void setPnl(BigDecimal pnl) { this.pnl = pnl; }

    public BigDecimal getPnlPercent() { return pnlPercent; }
    public void setPnlPercent(BigDecimal pnlPercent) { this.pnlPercent = pnlPercent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }

    public BigDecimal getCommission() { return commission; }
    public void setCommission(BigDecimal commission) { this.commission = commission; }

    public String getBinanceOrderId() { return binanceOrderId; }
    public void setBinanceOrderId(String binanceOrderId) { this.binanceOrderId = binanceOrderId; }

    public String getStopLossOrderId() { return stopLossOrderId; }
    public void setStopLossOrderId(String stopLossOrderId) { this.stopLossOrderId = stopLossOrderId; }

    public String getTakeProfitOrderId() { return takeProfitOrderId; }
    public void setTakeProfitOrderId(String takeProfitOrderId) { this.takeProfitOrderId = takeProfitOrderId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Helper methods
    public boolean isOpen() {
        return "OPEN".equals(this.status);
    }

    public void close(BigDecimal exitPrice, String exitReason, BigDecimal commission) {
        this.exitPrice = exitPrice;
        this.exitTime = LocalDateTime.now();
        this.exitReason = exitReason;
        this.commission = commission;
        this.status = "CLOSED";
        
        // Calculate P&L based on position direction
        if ("SHORT".equals(this.action)) {
            this.pnl = this.entryPrice.subtract(exitPrice).multiply(this.quantity).subtract(commission);
        } else {
            this.pnl = exitPrice.subtract(this.entryPrice).multiply(this.quantity).subtract(commission);
        }
        this.pnlPercent = this.pnl.divide(this.investedAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    @Override
    public String toString() {
        return String.format("Trade{id=%d, symbol='%s', action='%s', entryPrice=%s, status='%s', pnl=%s}",
                id, symbol, action, entryPrice, status, pnl);
    }
}
