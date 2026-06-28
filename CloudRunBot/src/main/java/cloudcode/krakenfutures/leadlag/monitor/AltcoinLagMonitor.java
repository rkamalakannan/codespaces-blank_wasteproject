package cloudcode.krakenfutures.leadlag.monitor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class AltcoinLagMonitor {

    private static final long WINDOW_DURATION_MS = 10_000; // 10 seconds

    private final Map<String, Deque<PricePoint>> priceWindows = new HashMap<>();

    public synchronized void onPriceTick(String altcoin, double price, long timestampMs) {
        Deque<PricePoint> window = priceWindows.computeIfAbsent(altcoin, k -> new ArrayDeque<>());
        window.addLast(new PricePoint(price, timestampMs));

        long cutoff = timestampMs - WINDOW_DURATION_MS;
        while (!window.isEmpty() && window.peekFirst().timestamp < cutoff) {
            window.pollFirst();
        }
    }

    /**
     * Returns the price change percentage over the last 10 seconds for a given altcoin.
     * Returns 0 if no data or insufficient data points.
     */
    public synchronized double get10SecChangePct(String altcoin) {
        Deque<PricePoint> window = priceWindows.get(altcoin);
        if (window == null || window.size() < 2) {
            return 0;
        }
        PricePoint oldest = window.peekFirst();
        PricePoint latest = window.peekLast();
        if (oldest == null || latest == null || oldest.price == 0) {
            return 0;
        }
        return (latest.price - oldest.price) / oldest.price * 100.0;
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