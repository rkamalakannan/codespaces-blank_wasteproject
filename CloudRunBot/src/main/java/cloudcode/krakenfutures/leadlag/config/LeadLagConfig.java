package cloudcode.krakenfutures.leadlag.config;

import java.util.List;

public class LeadLagConfig {
    private final double btcMoveThresholdPct;
    private final double orderBookImbalanceThreshold;
    private final double volumeSpikeMultiplier;
    private final long signalConsistencySec;
    private final List<String> approvedAltcoins;
    private final double positionSizeEur;
    private final double profitTargetPct;
    private final double stopLossPct;
    private final long maxHoldTimeSec;
    private final long cooldownSec;

    // Kraken fee schedule (percentage per trade leg)
    // Spot taker fee: 0.40% (Kraken Pro base tier, $0+ volume)
    // Futures taker fee: 0.05% (Kraken Futures base tier)
    private final double spotTakerFeePct;
    private final double futuresTakerFeePct;

    public LeadLagConfig() {
        this(List.of("ETH", "SOL", "XRP", "ADA", "DOGE", "LTC"));
    }

    public LeadLagConfig(List<String> approvedAltcoins) {
        this.btcMoveThresholdPct = 0.10; // 10.0% for initial testing
        this.orderBookImbalanceThreshold = 0.3; // 0.3
        this.volumeSpikeMultiplier = 2.0; // 2x
        this.signalConsistencySec = 5; // 5 seconds
        this.approvedAltcoins = approvedAltcoins != null ? List.copyOf(approvedAltcoins) : List.of();
        this.positionSizeEur = 1000.0; // €1,000
        this.profitTargetPct = 1.5; // +1.5%
        this.stopLossPct = -0.8; // -0.8%
        this.maxHoldTimeSec = 90; // 90 seconds
        this.cooldownSec = 60; // 60 seconds
        this.spotTakerFeePct = 0.40; // 0.40% per spot leg
        this.futuresTakerFeePct = 0.05; // 0.05% per futures leg
    }

    public double getBtcMoveThresholdPct() { return btcMoveThresholdPct; }
    public double getOrderBookImbalanceThreshold() { return orderBookImbalanceThreshold; }
    public double getVolumeSpikeMultiplier() { return volumeSpikeMultiplier; }
    public long getSignalConsistencySec() { return signalConsistencySec; }
    public List<String> getApprovedAltcoins() { return approvedAltcoins; }
    public double getPositionSizeEur() { return positionSizeEur; }
    public double getProfitTargetPct() { return profitTargetPct; }
    public double getStopLossPct() { return stopLossPct; }
    public long getMaxHoldTimeSec() { return maxHoldTimeSec; }
    public long getCooldownSec() { return cooldownSec; }
    public double getSpotTakerFeePct() { return spotTakerFeePct; }
    public double getFuturesTakerFeePct() { return futuresTakerFeePct; }

    /**
     * Round-trip fee for a spot-only trade (entry + exit both on spot).
     * Default: 0.40% + 0.40% = 0.80%.
     */
    public double getSpotRoundTripFeePct() {
        return spotTakerFeePct * 2.0;
    }

    /**
     * Round-trip fee for a spot entry + futures exit (or vice versa).
     * Default: 0.40% + 0.05% = 0.45%.
     */
    public double getCrossMarketRoundTripFeePct() {
        return spotTakerFeePct + futuresTakerFeePct;
    }

    /**
     * Returns the fee cost in EUR for a single trade leg given a position size.
     */
    public double getFeeCostEur(double positionSizeEur, boolean isFuturesLeg) {
        double feePct = isFuturesLeg ? futuresTakerFeePct : spotTakerFeePct;
        return positionSizeEur * feePct / 100.0;
    }

    /**
     * Returns the total round-trip fee cost in EUR for a spot-only trade.
     */
    public double getSpotRoundTripFeeCostEur() {
        return positionSizeEur * getSpotRoundTripFeePct() / 100.0;
    }

    /**
     * Returns the total round-trip fee cost in EUR for a cross-market trade.
     */
    public double getCrossMarketRoundTripFeeCostEur() {
        return positionSizeEur * getCrossMarketRoundTripFeePct() / 100.0;
    }
}
