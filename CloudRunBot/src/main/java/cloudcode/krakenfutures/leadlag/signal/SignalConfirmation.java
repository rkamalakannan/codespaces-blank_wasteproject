package cloudcode.krakenfutures.leadlag.signal;

import java.util.function.Consumer;
import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.leadlag.monitor.BtcOrderBookMonitor;
import cloudcode.krakenfutures.leadlag.monitor.BtcVolumeMonitor;

public class SignalConfirmation {

    private final LeadLagConfig config;
    private final BtcOrderBookMonitor orderBookMonitor;
    private final BtcVolumeMonitor volumeMonitor;

    private BtcSignal lastRawSignal = null;
    private long lastRawSignalTime = 0;
    private long signalStartTime = 0;

    public SignalConfirmation(LeadLagConfig config,
                              BtcOrderBookMonitor orderBookMonitor,
                              BtcVolumeMonitor volumeMonitor) {
        this.config = config;
        this.orderBookMonitor = orderBookMonitor;
        this.volumeMonitor = volumeMonitor;
    }

    /**
     * Receives a raw BTC signal and checks if it meets all confirmation criteria.
     * Emits a confirmed signal if all criteria are met.
     */
    public synchronized void onRawSignal(BtcSignal rawSignal, Consumer<BtcSignal> confirmedCallback) {
        long now = System.currentTimeMillis();

        if (lastRawSignal == null || lastRawSignal.getType() != rawSignal.getType()) {
            signalStartTime = now;
            lastRawSignal = rawSignal;
            lastRawSignalTime = now;
            return;
        }

        lastRawSignal = rawSignal;
        lastRawSignalTime = now;

        long consistencyDurationMs = config.getSignalConsistencySec() * 1000L;
        if (now - signalStartTime < consistencyDurationMs) {
            return; // Not yet confirmed for the required duration
        }

        // Check order book imbalance
        double imbalance = orderBookMonitor.computeImbalance(10);
        double imbalanceThreshold = config.getOrderBookImbalanceThreshold();

        boolean imbalanceBullishOk = rawSignal.getType() == SignalType.BULLISH && imbalance >= imbalanceThreshold;
        boolean imbalanceBearishOk = rawSignal.getType() == SignalType.BEARISH && imbalance <= -imbalanceThreshold;

        if (!imbalanceBullishOk && !imbalanceBearishOk) {
            return;
        }

        // Check volume spike
        if (!volumeMonitor.isVolumeSpikeDetected(config.getVolumeSpikeMultiplier())) {
            return;
        }

        // All criteria met — emit confirmed signal
        confirmedCallback.accept(rawSignal);

        // Reset to wait for the next signal
        lastRawSignal = null;
        signalStartTime = 0;
    }

    public synchronized BtcSignal getLastRawSignal() {
        return lastRawSignal;
    }
}