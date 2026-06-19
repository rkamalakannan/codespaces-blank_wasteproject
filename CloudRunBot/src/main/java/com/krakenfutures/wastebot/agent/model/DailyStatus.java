package com.krakenfutures.wastebot.agent.model;

public record DailyStatus(
    int tradesToday,
    double dailyPnl,
    int consecutiveLosses,
    boolean tradingAllowed
) {}
