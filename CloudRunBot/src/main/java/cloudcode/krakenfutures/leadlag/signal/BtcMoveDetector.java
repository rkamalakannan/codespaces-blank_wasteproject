package cloudcode.krakenfutures.leadlag.signal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;

public class BtcMoveDetector {

    private static final long WINDOW_DURATION_MS = 10_000; // 10 seconds

    private final LeadLagConfig config;
    private final Deque<PricePoint> priceWindow = new ArrayDeque<>();
    private long lastSignalTime = 0;
    private long lastSignalTimestamp = 0;

    public BtcMoveDetector(LeadLagConfig config) {
        this.config = config;
    }

    /**
     * Ingests a new BTC price tick.
     * 
     * @param price the current BTC price.
     * @param timestampMs the timestamp of the tick in milliseconds.
     * @param signalCallback the callback to invoke when a raw signal is detected.
     */
    public synchronized void onPriceTick(double price, long timestampMs, Consumer<BtcSignal> signalCallback) {
        priceWindow.addLast(new PricePoint(price, timestampMs));
        cleanWindow(timestampMs);

        if (priceWindow.size() < 2) {
            return;
        }

        PricePoint oldest = priceWindow.peekFirst();
        PricePoint latest = priceWindow.peekLast();

        if (oldest == null || latest == null || oldest.price == 0) {
            return;
        }

        double btcChange = (latest.price - oldest.price) / oldest.price * 100.0;
        double threshold = config.getBtcMoveThresholdPct();

        if (Math.abs(btcChange) >= threshold && (timestampMs - lastSignalTimestamp) >= 5000) {
            SignalType type = btcChange > 0 ? SignalType.BULLISH : SignalType.BEARISH;
            BtcSignal signal = new BtcSignal(type, btcChange, timestampMs);
            lastSignalTime = System.currentTimeMillis();
            lastSignalTimestamp = timestampMs;
            signalCallback.accept(signal);
        }
    }

    private void cleanWindow(long currentTimestamp) {
        long cutoff = currentTimestamp - WINDOW_DURATION_MS;
        while (!priceWindow.isEmpty() && priceWindow.peekFirst().timestamp < cutoff) {
            priceWindow.pollFirst();
        }
    }

    public synchronized long getLastSignalTime() {
        return lastSignalTime;
    }

    private static class PricePoint {
        final double price;
        final long timestamp;

        PricePoint(double price, long timestamp) {
            this.price = price;
            this.timestamp = timestamp;
        }
    }
}