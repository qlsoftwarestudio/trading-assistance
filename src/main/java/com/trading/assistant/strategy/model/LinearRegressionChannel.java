package com.trading.assistant.strategy.model;

public class LinearRegressionChannel {

    public enum ChannelDirection { UP, DOWN, SIDEWAYS }

    private final double slope;             // price change per candle (absolute)
    private final double slopePct;          // slope as % of midline per candle
    private final double midline;           // regression line value at last bar
    private final double upperBand;         // midline + 1 stddev
    private final double lowerBand;         // midline - 1 stddev
    private final double projectedMidline;  // midline projected N candles ahead
    private final double channelWidthPct;   // (upper - lower) / midline × 100
    private final double pricePosition;     // 0.0 = at lower band, 1.0 = at upper band
    private final ChannelDirection direction;
    private final int lookbackBars;

    public LinearRegressionChannel(double slope, double slopePct, double midline,
                                   double upperBand, double lowerBand, double projectedMidline,
                                   double channelWidthPct, double pricePosition,
                                   ChannelDirection direction, int lookbackBars) {
        this.slope = slope;
        this.slopePct = slopePct;
        this.midline = midline;
        this.upperBand = upperBand;
        this.lowerBand = lowerBand;
        this.projectedMidline = projectedMidline;
        this.channelWidthPct = channelWidthPct;
        this.pricePosition = pricePosition;
        this.direction = direction;
        this.lookbackBars = lookbackBars;
    }

    public double getSlope()              { return slope; }
    public double getSlopePct()           { return slopePct; }
    public double getMidline()            { return midline; }
    public double getUpperBand()          { return upperBand; }
    public double getLowerBand()          { return lowerBand; }
    public double getProjectedMidline()   { return projectedMidline; }
    public double getChannelWidthPct()    { return channelWidthPct; }
    public double getPricePosition()      { return pricePosition; }
    public ChannelDirection getDirection(){ return direction; }
    public int getLookbackBars()          { return lookbackBars; }

    public String toLogString() {
        String dirEmoji = direction == ChannelDirection.UP ? "📈"
                : direction == ChannelDirection.DOWN ? "📉" : "➡️";
        return String.format(
                "%s Canal Regresión (%d velas) | Slope: %+.4f/vela (%+.3f%%) | " +
                "Lower=%.4f  Mid=%.4f  Upper=%.4f | " +
                "Precio en canal: %.0f%% | Proyección mid: %.4f",
                dirEmoji, lookbackBars, slope, slopePct,
                lowerBand, midline, upperBand,
                pricePosition * 100, projectedMidline);
    }

    public String toAlertString() {
        String dirLabel = direction == ChannelDirection.UP ? "ALCISTA 📈"
                : direction == ChannelDirection.DOWN ? "BAJISTA 📉" : "LATERAL ➡️";
        String posLabel;
        if (pricePosition < 0.25)      posLabel = "zona baja 🟢 (LONG favorecido)";
        else if (pricePosition > 0.75) posLabel = "zona alta 🔴 (SHORT favorecido)";
        else                           posLabel = "zona media";

        return String.format(
                "📉 <b>Canal de Regresión</b> (%d velas)\n" +
                "Tendencia: %s  (slope: %+.4f/vela)\n" +
                "Lower: $%.4f  |  Mid: $%.4f  |  Upper: $%.4f\n" +
                "Precio en canal: %.0f%% → %s",
                lookbackBars, dirLabel, slope,
                lowerBand, midline, upperBand,
                pricePosition * 100, posLabel);
    }
}
