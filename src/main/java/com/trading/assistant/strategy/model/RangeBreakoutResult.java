package com.trading.assistant.strategy.model;

public class RangeBreakoutResult {

    private final String direction; // "LONG" or "SHORT"
    private final double rangeHigh;
    private final double rangeLow;
    private final double rangePct;
    private final double volumeRatio;
    private final double breakoutSl;  // SL price: just inside range

    public RangeBreakoutResult(String direction, double rangeHigh, double rangeLow,
                               double rangePct, double volumeRatio, double breakoutSl) {
        this.direction   = direction;
        this.rangeHigh   = rangeHigh;
        this.rangeLow    = rangeLow;
        this.rangePct    = rangePct;
        this.volumeRatio = volumeRatio;
        this.breakoutSl  = breakoutSl;
    }

    public String getDirection()   { return direction; }
    public double getRangeHigh()   { return rangeHigh; }
    public double getRangeLow()    { return rangeLow; }
    public double getRangePct()    { return rangePct; }
    public double getVolumeRatio() { return volumeRatio; }
    public double getBreakoutSl()  { return breakoutSl; }
}
