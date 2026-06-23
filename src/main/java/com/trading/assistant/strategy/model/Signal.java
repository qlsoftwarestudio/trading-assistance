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

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 10)
    private String action; // LONG or HOLD

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal price;

    @Column(precision = 5, scale = 2)
    private BigDecimal rsi;

    @Column(precision = 20, scale = 8)
    private BigDecimal sessionLow;

    @Column(precision = 20, scale = 8)
    private BigDecimal sessionHigh;

    @Column(precision = 10, scale = 4)
    private BigDecimal momentum;

    private Boolean inBuyZone;

    private Boolean inSellZone;

    @Column(nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    private Boolean executed;

    @Column(name = "trade_id")
    private Long tradeId;

    @Column(length = 30)
    private String setupType; // MEAN_REVERSION, BREAKOUT, TREND_DIP

    // --- Market Context at signal generation ---
    @Column(length = 10)
    private String trend1h; // UP, DOWN, SIDEWAYS

    @Column(length = 10)
    private String trend4h;

    @Column(length = 10)
    private String trend1d;

    @Column(precision = 5, scale = 2)
    private BigDecimal relativeVolume;

    @Column(precision = 10, scale = 4)
    private BigDecimal btcCorrelation;

    @Column(length = 10)
    private String btcTrend1d;

    private Boolean confluence;

    @Column(precision = 10, scale = 4)
    private BigDecimal distanceToSupportPct;

    @Column(precision = 10, scale = 4)
    private BigDecimal distanceToResistancePct;

    @Transient
    private String projectionNote;

    @Transient
    private BigDecimal bbUpper;

    @Transient
    private BigDecimal bbMid;

    @Transient
    private BigDecimal bbLower;

    @Transient
    private Double stochK5m;

    @Transient
    private Double stochD5m;

    @PrePersist
    protected void onCreate() {
        generatedAt = LocalDateTime.now();
        executed = false;
    }

    // Constructors
    public Signal() {}

    public Signal(String symbol, String action, BigDecimal price, BigDecimal rsi,
                  BigDecimal sessionLow, BigDecimal sessionHigh, BigDecimal momentum,
                  Boolean inBuyZone, Boolean inSellZone) {
        this.symbol = symbol;
        this.action = action;
        this.price = price;
        this.rsi = rsi;
        this.sessionLow = sessionLow;
        this.sessionHigh = sessionHigh;
        this.momentum = momentum;
        this.inBuyZone = inBuyZone;
        this.inSellZone = inSellZone;
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

    public BigDecimal getSessionHigh() { return sessionHigh; }
    public void setSessionHigh(BigDecimal sessionHigh) { this.sessionHigh = sessionHigh; }

    public Boolean getInSellZone() { return inSellZone; }
    public void setInSellZone(Boolean inSellZone) { this.inSellZone = inSellZone; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }

    public Boolean getExecuted() { return executed; }
    public void setExecuted(Boolean executed) { this.executed = executed; }

    public Long getTradeId() { return tradeId; }
    public void setTradeId(Long tradeId) { this.tradeId = tradeId; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }

    // Helper methods
    public boolean isLongSignal() {
        return "LONG".equals(this.action);
    }

    public boolean isShortSignal() {
        return "SHORT".equals(this.action);
    }

    // Market context getters/setters
    public String getTrend1h() { return trend1h; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public void setTrend1h(String trend1h) { this.trend1h = trend1h; }

    public String getTrend4h() { return trend4h; }
    public void setTrend4h(String trend4h) { this.trend4h = trend4h; }

    public String getTrend1d() { return trend1d; }
    public void setTrend1d(String trend1d) { this.trend1d = trend1d; }

    public BigDecimal getRelativeVolume() { return relativeVolume; }
    public void setRelativeVolume(BigDecimal relativeVolume) { this.relativeVolume = relativeVolume; }

    public BigDecimal getBtcCorrelation() { return btcCorrelation; }
    public void setBtcCorrelation(BigDecimal btcCorrelation) { this.btcCorrelation = btcCorrelation; }

    public String getBtcTrend1d() { return btcTrend1d; }
    public void setBtcTrend1d(String btcTrend1d) { this.btcTrend1d = btcTrend1d; }

    public Boolean getConfluence() { return confluence; }
    public void setConfluence(Boolean confluence) { this.confluence = confluence; }

    public BigDecimal getDistanceToSupportPct() { return distanceToSupportPct; }
    public void setDistanceToSupportPct(BigDecimal distanceToSupportPct) { this.distanceToSupportPct = distanceToSupportPct; }

    public BigDecimal getDistanceToResistancePct() { return distanceToResistancePct; }
    public void setDistanceToResistancePct(BigDecimal distanceToResistancePct) { this.distanceToResistancePct = distanceToResistancePct; }

    public String getProjectionNote() { return projectionNote; }
    public void setProjectionNote(String projectionNote) { this.projectionNote = projectionNote; }

    public BigDecimal getBbUpper() { return bbUpper; }
    public void setBbUpper(BigDecimal bbUpper) { this.bbUpper = bbUpper; }

    public BigDecimal getBbMid() { return bbMid; }
    public void setBbMid(BigDecimal bbMid) { this.bbMid = bbMid; }

    public BigDecimal getBbLower() { return bbLower; }
    public void setBbLower(BigDecimal bbLower) { this.bbLower = bbLower; }

    public Double getStochK5m() { return stochK5m; }
    public void setStochK5m(Double stochK5m) { this.stochK5m = stochK5m; }

    public Double getStochD5m() { return stochD5m; }
    public void setStochD5m(Double stochD5m) { this.stochD5m = stochD5m; }

    @Override
    public String toString() {
        return String.format("Signal{id=%d, symbol='%s', action='%s', price=%s, rsi=%s, generatedAt=%s}",
                id, symbol, action, price, rsi, generatedAt);
    }
}
