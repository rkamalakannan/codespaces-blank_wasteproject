package com.krakenfutures.bot.agent.model;

public record IndicatorSnapshot(
    double ema9,
    double ema21,
    double rsi,
    double vwap,
    double bbUpper,
    double bbMiddle,
    double bbLower,
    double volumeRatio,
    double atr
) {
    public static IndicatorSnapshot empty() {
        return new IndicatorSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
