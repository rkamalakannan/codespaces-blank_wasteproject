package cloudcode.krakenfutures.leadlag.selector;

import java.util.List;
import java.util.Map;

import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.leadlag.monitor.AltcoinLagMonitor;

public class AltcoinSelector {

    // Scoring weights
    private static final double W_CORRELATION = 0.35;
    private static final double W_LIQUIDITY = 0.30;
    private static final double W_LAG_BONUS = 0.25;
    private static final double W_SPREAD_PENALTY = 0.10;

    // Maximum spread accepted for trading
    private static final double MAX_SPREAD_PCT = 0.3;

    private final LeadLagConfig config;
    private final CorrelationTracker correlationTracker;
    private final AltcoinLagMonitor lagMonitor;
    private final Map<String, Double> liquidityScores;

    public AltcoinSelector(LeadLagConfig config,
                           CorrelationTracker correlationTracker,
                           AltcoinLagMonitor lagMonitor,
                           Map<String, Double> liquidityScores) {
        this.config = config;
        this.correlationTracker = correlationTracker;
        this.lagMonitor = lagMonitor;
        this.liquidityScores = liquidityScores;
    }

    /**
     * Scores each approved altcoin and returns the best candidate, or null if none qualifies.
     * 
     * @param altcoinSpreads map of altcoin symbol to its current bid-ask spread in percent.
     * @param signalDirection "BULLISH" or "BEARISH" — we prefer altcoins lagging in the opposite direction.
     */
    public AltcoinCandidate selectBest(Map<String, Double> altcoinSpreads, String signalDirection) {
        AltcoinCandidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        List<String> approved = config.getApprovedAltcoins();
        for (String altcoin : approved) {
            double spread = altcoinSpreads.getOrDefault(altcoin, 100.0);
            if (spread >= MAX_SPREAD_PCT) {
                continue; // Skip — spread too wide
            }

            double correlation = Math.abs(correlationTracker.getCorrelation(altcoin));
            double liquidity = liquidityScores.getOrDefault(altcoin, 0.0);

            // Lag bonus: altcoin that has NOT moved in the signal direction gets a bonus
            double altcoinChange = lagMonitor.get10SecChangePct(altcoin);
            double lagBonus = computeLagBonus(altcoinChange, signalDirection);

            // Spread penalty: wider spread → lower score
            double spreadPenalty = spread / MAX_SPREAD_PCT;

            double totalScore = W_CORRELATION * correlation
                    + W_LIQUIDITY * liquidity
                    + W_LAG_BONUS * lagBonus
                    - W_SPREAD_PENALTY * spreadPenalty;

            AltcoinCandidate candidate = new AltcoinCandidate(
                    altcoin, correlation, liquidity, lagBonus, spread, totalScore);

            if (totalScore > bestScore) {
                bestScore = totalScore;
                best = candidate;
            }
        }

        return best;
    }

    private double computeLagBonus(double altcoinChange, String signalDirection) {
        // If signal is BULLISH and altcoin hasn't moved up yet (change <= 0), it is lagging → bonus
        // If signal is BEARISH and altcoin hasn't moved down yet (change >= 0), it is lagging → bonus
        if ("BULLISH".equals(signalDirection) && altcoinChange <= 0) {
            return 1.0 + Math.min(Math.abs(altcoinChange) / 2.0, 1.0);
        } else if ("BEARISH".equals(signalDirection) && altcoinChange >= 0) {
            return 1.0 + Math.min(Math.abs(altcoinChange) / 2.0, 1.0);
        }
        // Already moved — low or no bonus
        return 0.0;
    }
}