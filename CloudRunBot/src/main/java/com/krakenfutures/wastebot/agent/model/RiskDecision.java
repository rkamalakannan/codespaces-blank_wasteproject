package com.krakenfutures.wastebot.agent.model;

import java.util.List;

public record RiskDecision(
    boolean approved,
    String reason,
    Double modifiedSl,
    Double modifiedTp1,
    Double modifiedTp2,
    Integer modifiedContracts,
    double riskAmountEur,
    double riskPercent,
    DailyStatus dailyStatus,
    List<String> warnings,
    String logLine
) {
    public static RiskDecision rejected(String reason) {
        return new RiskDecision(
            false, reason, null, null, null, null,
            0, 0, new DailyStatus(0, 0, 0, false),
            List.of(),
            "[RISK] Approved=false | Reason=" + reason
        );
    }

    public static RiskDecision approved(double riskAmount, double riskPct, DailyStatus status) {
        return new RiskDecision(
            true, "All checks passed", null, null, null, null,
            riskAmount, riskPct, status,
            List.of(),
            "[RISK] Approved=true | Risk=" + String.format("%.2f%%", riskPct)
        );
    }
}
