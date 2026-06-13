package com.trading.assistant.portfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trade_journal")
public class TradeJournal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tradeId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String action; // LONG / SHORT

    @Column(nullable = false, length = 30)
    private String setupType; // MEAN_REVERSION, BREAKOUT, TREND_DIP

    @Column(precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal exitPrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal pnl;

    @Column(precision = 10, scale = 4)
    private BigDecimal pnlPercent;

    @Column(precision = 10, scale = 4)
    private BigDecimal entryRsi;

    @Column(precision = 10, scale = 4)
    private BigDecimal entryVolumeRatio;

    @Column(precision = 10, scale = 6)
    private BigDecimal entryMomentum;

    @Column(length = 10)
    private String trend1h; // UP, DOWN, NEUTRAL

    @Column(length = 10)
    private String trend4h; // UP, DOWN, NEUTRAL

    @Column(length = 10)
    private String trend1d; // UP, DOWN, NEUTRAL

    @Column(precision = 10, scale = 6)
    private BigDecimal atrAtEntry;

    @Column(precision = 10, scale = 4)
    private BigDecimal slDistancePct; // SL distance from entry as %

    @Column(precision = 10, scale = 4)
    private BigDecimal tpDistancePct; // TP distance from entry as %

    @Column
    private Boolean inBuyZone;

    @Column
    private Boolean inSellZone;

    @Column
    private Boolean vwapFilterPassed;

    @Column
    private Boolean regressionFilterPassed;

    @Column(length = 20)
    private String exitReason; // STOP_LOSS, TAKE_PROFIT, TIME_EXIT, MOMENTUM_EXIT

    @Column
    private LocalDateTime exitTime;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public TradeJournal() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTradeId() { return tradeId; }
    public void setTradeId(Long tradeId) { this.tradeId = tradeId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }

    public BigDecimal getPnl() { return pnl; }
    public void setPnl(BigDecimal pnl) { this.pnl = pnl; }

    public BigDecimal getPnlPercent() { return pnlPercent; }
    public void setPnlPercent(BigDecimal pnlPercent) { this.pnlPercent = pnlPercent; }

    public BigDecimal getEntryRsi() { return entryRsi; }
    public void setEntryRsi(BigDecimal entryRsi) { this.entryRsi = entryRsi; }

    public BigDecimal getEntryVolumeRatio() { return entryVolumeRatio; }
    public void setEntryVolumeRatio(BigDecimal entryVolumeRatio) { this.entryVolumeRatio = entryVolumeRatio; }

    public BigDecimal getEntryMomentum() { return entryMomentum; }
    public void setEntryMomentum(BigDecimal entryMomentum) { this.entryMomentum = entryMomentum; }

    public String getTrend1h() { return trend1h; }
    public void setTrend1h(String trend1h) { this.trend1h = trend1h; }

    public String getTrend4h() { return trend4h; }
    public void setTrend4h(String trend4h) { this.trend4h = trend4h; }

    public String getTrend1d() { return trend1d; }
    public void setTrend1d(String trend1d) { this.trend1d = trend1d; }

    public BigDecimal getAtrAtEntry() { return atrAtEntry; }
    public void setAtrAtEntry(BigDecimal atrAtEntry) { this.atrAtEntry = atrAtEntry; }

    public BigDecimal getSlDistancePct() { return slDistancePct; }
    public void setSlDistancePct(BigDecimal slDistancePct) { this.slDistancePct = slDistancePct; }

    public BigDecimal getTpDistancePct() { return tpDistancePct; }
    public void setTpDistancePct(BigDecimal tpDistancePct) { this.tpDistancePct = tpDistancePct; }

    public Boolean getInBuyZone() { return inBuyZone; }
    public void setInBuyZone(Boolean inBuyZone) { this.inBuyZone = inBuyZone; }

    public Boolean getInSellZone() { return inSellZone; }
    public void setInSellZone(Boolean inSellZone) { this.inSellZone = inSellZone; }

    public Boolean getVwapFilterPassed() { return vwapFilterPassed; }
    public void setVwapFilterPassed(Boolean vwapFilterPassed) { this.vwapFilterPassed = vwapFilterPassed; }

    public Boolean getRegressionFilterPassed() { return regressionFilterPassed; }
    public void setRegressionFilterPassed(Boolean regressionFilterPassed) { this.regressionFilterPassed = regressionFilterPassed; }

    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }

    public LocalDateTime getExitTime() { return exitTime; }
    public void setExitTime(LocalDateTime exitTime) { this.exitTime = exitTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
