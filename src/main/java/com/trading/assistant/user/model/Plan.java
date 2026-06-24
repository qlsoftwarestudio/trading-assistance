package com.trading.assistant.user.model;

public enum Plan {
    FREE(1, 500.0),
    STARTER(1, 2000.0),
    PRO(3, 10000.0),
    ENTERPRISE(5, 0.0); // 0 = unlimited

    private final int maxBots;
    private final double maxCapitalUsd;

    Plan(int maxBots, double maxCapitalUsd) {
        this.maxBots = maxBots;
        this.maxCapitalUsd = maxCapitalUsd;
    }

    public int getMaxBots() { return maxBots; }
    public double getMaxCapitalUsd() { return maxCapitalUsd; }
}
