package com.trading.assistant.strategy.model;

import java.math.BigDecimal;

/**
 * Contexto macro del mercado al momento de generar una senal.
 * Permite filtrar operaciones contra-tendencia o en zonas de riesgo.
 */
public class MarketContext {

    private String timeframe;           // 15m, 1h, 4h, 1d
    private TrendDirection trend1h;   // UP, DOWN, SIDEWAYS
    private TrendDirection trend4h;
    private TrendDirection trend1d;

    private BigDecimal ema20_1h;
    private BigDecimal ema50_1h;
    private BigDecimal ema200_1h;

    private double relativeVolume;    // volumen vs promedio (ej: 1.5 = 50% arriba)
    private double obvSlope;          // pendiente del OBV

    private BigDecimal nearestSupport;
    private BigDecimal nearestResistance;
    private double distanceToSupportPct;
    private double distanceToResistancePct;

    private double btcCorrelation;    // correlacion con BTC (0 a 1)
    private TrendDirection btcTrend1d;

    private boolean confluence;       // al menos 2 timeframes coinciden en direccion

    public enum TrendDirection {
        UP, DOWN, SIDEWAYS
    }

    public MarketContext() {}

    // Getters y Setters
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public TrendDirection getTrend1h() { return trend1h; }
    public void setTrend1h(TrendDirection trend1h) { this.trend1h = trend1h; }

    public TrendDirection getTrend4h() { return trend4h; }
    public void setTrend4h(TrendDirection trend4h) { this.trend4h = trend4h; }

    public TrendDirection getTrend1d() { return trend1d; }
    public void setTrend1d(TrendDirection trend1d) { this.trend1d = trend1d; }

    public BigDecimal getEma20_1h() { return ema20_1h; }
    public void setEma20_1h(BigDecimal ema20_1h) { this.ema20_1h = ema20_1h; }

    public BigDecimal getEma50_1h() { return ema50_1h; }
    public void setEma50_1h(BigDecimal ema50_1h) { this.ema50_1h = ema50_1h; }

    public BigDecimal getEma200_1h() { return ema200_1h; }
    public void setEma200_1h(BigDecimal ema200_1h) { this.ema200_1h = ema200_1h; }

    public double getRelativeVolume() { return relativeVolume; }
    public void setRelativeVolume(double relativeVolume) { this.relativeVolume = relativeVolume; }

    public double getObvSlope() { return obvSlope; }
    public void setObvSlope(double obvSlope) { this.obvSlope = obvSlope; }

    public BigDecimal getNearestSupport() { return nearestSupport; }
    public void setNearestSupport(BigDecimal nearestSupport) { this.nearestSupport = nearestSupport; }

    public BigDecimal getNearestResistance() { return nearestResistance; }
    public void setNearestResistance(BigDecimal nearestResistance) { this.nearestResistance = nearestResistance; }

    public double getDistanceToSupportPct() { return distanceToSupportPct; }
    public void setDistanceToSupportPct(double distanceToSupportPct) { this.distanceToSupportPct = distanceToSupportPct; }

    public double getDistanceToResistancePct() { return distanceToResistancePct; }
    public void setDistanceToResistancePct(double distanceToResistancePct) { this.distanceToResistancePct = distanceToResistancePct; }

    public double getBtcCorrelation() { return btcCorrelation; }
    public void setBtcCorrelation(double btcCorrelation) { this.btcCorrelation = btcCorrelation; }

    public TrendDirection getBtcTrend1d() { return btcTrend1d; }
    public void setBtcTrend1d(TrendDirection btcTrend1d) { this.btcTrend1d = btcTrend1d; }

    public boolean isConfluence() { return confluence; }
    public void setConfluence(boolean confluence) { this.confluence = confluence; }

    /**
     * Determina si el contexto aprueba una entrada LONG.
     */
    public boolean supportsLong() {
        if (trend1d == TrendDirection.DOWN || trend4h == TrendDirection.DOWN) {
            return false; // No operar LONG contra tendencia mayor
        }
        if (btcTrend1d == TrendDirection.DOWN && btcCorrelation > 0.6) {
            return false; // BTC cayendo y alta correlacion = riesgo
        }
        return true;
    }

    /**
     * Determina si el contexto aprueba una entrada SHORT.
     */
    public boolean supportsShort() {
        if (trend1d == TrendDirection.UP || trend4h == TrendDirection.UP) {
            return false; // No operar SHORT contra tendencia mayor
        }
        if (btcTrend1d == TrendDirection.UP && btcCorrelation > 0.6) {
            return false; // BTC subiendo y alta correlacion = riesgo
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format(
                "MarketContext{1h=%s, 4h=%s, 1d=%s, vol=%.2fx, BTC_corr=%.2f, confluence=%b}",
                trend1h, trend4h, trend1d, relativeVolume, btcCorrelation, confluence);
    }
}
