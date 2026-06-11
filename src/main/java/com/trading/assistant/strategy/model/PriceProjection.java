package com.trading.assistant.strategy.model;

public class PriceProjection {

    private final double projectedHigh;
    private final double projectedLow;
    private final double atr;
    private final int candlesAhead;
    private final boolean tpReachableLong;
    private final boolean tpReachableShort;

    public PriceProjection(double projectedHigh, double projectedLow, double atr, int candlesAhead,
                           boolean tpReachableLong, boolean tpReachableShort) {
        this.projectedHigh = projectedHigh;
        this.projectedLow = projectedLow;
        this.atr = atr;
        this.candlesAhead = candlesAhead;
        this.tpReachableLong = tpReachableLong;
        this.tpReachableShort = tpReachableShort;
    }

    public double getProjectedHigh() { return projectedHigh; }
    public double getProjectedLow()  { return projectedLow; }
    public double getAtr()            { return atr; }
    public int getCandlesAhead()      { return candlesAhead; }
    public boolean isTpReachableLong()  { return tpReachableLong; }
    public boolean isTpReachableShort() { return tpReachableShort; }

    public String toLogString() {
        return String.format(
                "ATR=%.4f | Range next %d candles: [%.4f — %.4f] | TP reachable: LONG=%s SHORT=%s",
                atr, candlesAhead, projectedLow, projectedHigh,
                tpReachableLong ? "✅" : "❌",
                tpReachableShort ? "✅" : "❌");
    }

    public String toAlertString() {
        int minutesAhead = candlesAhead * 5;
        String tpLong  = tpReachableLong  ? "✅" : "❌";
        String tpShort = tpReachableShort ? "✅" : "❌";
        return String.format(
                "📊 <b>Proyección ATR</b> (próximos %d min)\n" +
                "Rango esperado: $%.4f — $%.4f\n" +
                "ATR: %.4f\n" +
                "TP alcanzable: LONG %s  |  SHORT %s",
                minutesAhead, projectedLow, projectedHigh, atr, tpLong, tpShort);
    }
}
