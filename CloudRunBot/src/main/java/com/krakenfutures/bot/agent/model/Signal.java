package com.krakenfutures.bot.agent.model;

public record Signal(
    String signal,
    String action,
    int confidence,
    double entryPrice,
    double stopLoss,
    double takeProfit1,
    double takeProfit2,
    double takeProfit3,
    int contracts,
    double riskReward,
    double atr,
    String asset,
    String reason,
    String logLine,
    IndicatorSnapshot indicators,
    boolean slTpVerified,
    boolean safeToTrade
) {
    public static final String SIGNAL_NONE = "NONE";
    public static final String SIGNAL_LONG = "LONG";
    public static final String SIGNAL_SHORT = "SHORT";
    public static final String SIGNAL_EXIT = "EXIT";
    public static final String SIGNAL_BUY = "BUY";
    public static final String SIGNAL_SELL = "SELL";
    public static final String SIGNAL_HOLD = "HOLD";

    public static final String ACTION_NONE = "NONE";
    public static final String ACTION_ENTER = "ENTER";
    public static final String ACTION_EXIT = "EXIT";
    public static final String ACTION_HOLD = "HOLD";
    public static final String ACTION_WAIT = "WAIT";
    public static final String ACTION_ROTATE = "ROTATE";

    public static Signal none(String reason) {
        return new Signal(
            SIGNAL_NONE, ACTION_WAIT, 0, 0, 0, 0, 0, 0,
            0, 0, 0, "", reason,
            "[SKIP] Reason=" + reason,
            IndicatorSnapshot.empty(),
            false,
            false
        );
    }

    public static Signal none(String reason, String asset) {
        return new Signal(
            SIGNAL_NONE, ACTION_WAIT, 0, 0, 0, 0, 0, 0,
            0, 0, 0, asset, reason,
            "[SKIP] Asset=" + asset + " | Reason=" + reason,
            IndicatorSnapshot.empty(),
            false,
            false
        );
    }
}
