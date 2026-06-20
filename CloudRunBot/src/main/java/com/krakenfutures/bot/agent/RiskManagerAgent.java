//package com.krakenfutures.wastebot.agent;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.krakenfutures.wastebot.agent.model.DailyStatus;
//import com.krakenfutures.wastebot.agent.model.RiskDecision;
//import com.krakenfutures.wastebot.agent.model.Signal;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.ArrayList;
//import java.util.List;
//
//public class RiskManagerAgent {
//    private static final Logger log = LoggerFactory.getLogger(RiskManagerAgent.class);
//
//    private final ObjectMapper objectMapper;
//
//    // Tracking state
//    private double dailyPnl = 0.0;
//    private int tradesToday = 0;
//    private int consecutiveLosses = 0;
//    private double initialStartBalance = 10000.0;
//    private boolean tradingAllowed = true;
//
//    public RiskManagerAgent(ObjectMapper objectMapper) {
//        this.objectMapper = objectMapper;
//    }
//
//    public synchronized void updateSessionState(double pnl, int additionalTrades, boolean isLoss) {
//        this.dailyPnl += pnl;
//        this.tradesToday += additionalTrades;
//        if (isLoss) {
//            this.consecutiveLosses++;
//        } else if (pnl > 0) {
//            this.consecutiveLosses = 0;
//        }
//
//        // Check if stops or profit targets are hit
//        double maxLossLimit = -(initialStartBalance * 0.03);
//        double maxGainTarget = initialStartBalance * 0.05;
//
//        if (dailyPnl <= maxLossLimit) {
//            tradingAllowed = false;
//            log.warn("[RISK] Daily loss limit hit (3%). Trading stopped.");
//        } else if (dailyPnl >= maxGainTarget) {
//            tradingAllowed = false;
//            log.info("[RISK] Daily profit target hit (5%). Session locked.");
//        } else if (consecutiveLosses >= 5) {
//            tradingAllowed = false;
//            log.warn("[RISK] 5 consecutive losses. Trading stopped (Emergency Stop).");
//        } else if (tradesToday >= 20) {
//            tradingAllowed = false;
//            log.warn("[RISK] Overtrading limit (20 trades). Session locked.");
//        }
//    }
//
//    public synchronized void setInitialBalance(double balance) {
//        this.initialStartBalance = balance;
//        this.tradingAllowed = true;
//    }
//
//    public synchronized RiskDecision validate(Signal signal, double balance, int currentOpenPositionsCount) {
//        DailyStatus status = new DailyStatus(tradesToday, dailyPnl, consecutiveLosses, tradingAllowed);
//        List<String> warnings = new ArrayList<>();
//
//        if (!tradingAllowed) {
//            return new RiskDecision(
//                false, "Trading disabled due to limit breach", null, null, null, null,
//                0, 0, status, warnings,
//                "[RISK] Rejected=disabled | Reason=Daily state limits hit"
//            );
//        }
//
//        if (signal.signal().equals(Signal.SIGNAL_NONE)) {
//            return RiskDecision.rejected("No signal to evaluate");
//        }
//
//        // Action exit or hold can proceed without validation
//        if (signal.action().equals(Signal.ACTION_EXIT) || signal.action().equals(Signal.ACTION_HOLD)) {
//            return new RiskDecision(
//                true, "Exit or hold approved", null, null, null, null,
//                0, 0, status, warnings,
//                "[RISK] Approved Exit/Hold | Reason=Non-entry action"
//            );
//        }
//
//        // Validate basic rules
//        if (signal.stopLoss() == 0 || signal.takeProfit1() == 0) {
//            return RiskDecision.rejected("SL and TP values must be calculated and non-zero");
//        }
//
//        // Account limits check
//        int maxPositions = signal.asset().equals("MNQ") ? 1 : 3;
//        if (currentOpenPositionsCount >= maxPositions) {
//            return RiskDecision.rejected("Max open positions limit reached: " + maxPositions);
//        }
//
//        // Calculate and verify risk percent & amount
//        double stopDistance = Math.abs(signal.entryPrice() - signal.stopLoss());
//        double riskAmount;
//        double riskPct;
//
//        if (signal.asset().equals("MNQ")) {
//            // Futures risk evaluation: distance * contract multiplier * quantity
//            riskAmount = stopDistance * 2.0 * signal.contracts();
//            riskPct = (riskAmount / balance) * 100.0;
//        } else {
//            // Spot risk evaluation (Conservative Phase 1 limit or general)
//            riskAmount = stopDistance * signal.contracts(); // Simulated qty
//            riskPct = (riskAmount / balance) * 100.0;
//        }
//
//        // Strict: NEVER approve trade violating 1% risk rule
//        if (riskPct > 1.0) {
//            double maxAllowedContractsCandidate = (balance * 0.01) / (stopDistance * (signal.asset().equals("MNQ") ? 2.0 : 1.0));
//            int modifiedContracts = (int) Math.floor(maxAllowedContractsCandidate);
//            if (modifiedContracts < 1) modifiedContracts = 1;
//
//            if (signal.asset().equals("MNQ")) {
//                double recalculatedRisk = stopDistance * 2.0 * modifiedContracts;
//                if ((recalculatedRisk / balance) * 100.0 <= 1.0) {
//                    warnings.add("Modified contracts from " + signal.contracts() + " to " + modifiedContracts + " to satisfy 1% risk limit.");
//                    return new RiskDecision(
//                        true, "Approved with modified position sizing", null, null, null, modifiedContracts,
//                        recalculatedRisk, (recalculatedRisk / balance) * 100.0, status, warnings,
//                        String.format("[RISK] Modified Contracts= %d | Risk=%.2f%% | Reason=1%% Cap compliance", modifiedContracts, (recalculatedRisk / balance) * 100.0)
//                    );
//                }
//            }
//
//            return RiskDecision.rejected(String.format("Risk exceeds 1%% of account balance (Risk: %.2f%%)", riskPct));
//        }
//
//        // R:R limit: MUST be >= 1.5
//        if (signal.riskReward() < 1.5) {
//            // Suggest modified take_profit_2 to guarantee R:R of 1.5
//            double modifiedTp2;
//            if (signal.asset().equals("MNQ")) {
//                boolean isLong = signal.signal().equals(Signal.SIGNAL_LONG);
//                if (isLong) {
//                    modifiedTp2 = signal.entryPrice() + (stopDistance * 1.5);
//                } else {
//                    modifiedTp2 = signal.entryPrice() - (stopDistance * 1.5);
//                }
//            } else {
//                modifiedTp2 = signal.entryPrice() + (stopDistance * 1.5);
//            }
//
//            warnings.add("Modified TP2 from " + signal.takeProfit2() + " to " + modifiedTp2 + " to achieve target R:R of 1.5.");
//            return new RiskDecision(
//                true, "Approved with modified Take Profit 2", null, null, modifiedTp2, null,
//                riskAmount, riskPct, status, warnings,
//                String.format("[RISK] Modified TP2=%.2f | Expected R:R=1.5 | Reason=R:R compliance", modifiedTp2)
//            );
//        }
//
//        // Specific limits check
//        if (signal.indicators().rsi() > 70 || signal.indicators().rsi() < 30) {
//            return RiskDecision.rejected("RSI is extreme: " + signal.indicators().rsi());
//        }
//        if (signal.indicators().volumeRatio() < 1.2) {
//            return RiskDecision.rejected("Volume ratio is below required 1.2");
//        }
//        if (signal.confidence() < 60) {
//            return RiskDecision.rejected("Confidence is below required 60%");
//        }
//
//        // News Blackout simulator/verifier: block trades inside designated windows
//        // Since we are running in real-time or simulated, check environment or mock
//        boolean newsBlackout = false; // Mocked check
//        if (newsBlackout) {
//            return RiskDecision.rejected("Under active news blackout window");
//        }
//
//        return RiskDecision.approved(riskAmount, riskPct, status);
//    }
//}
