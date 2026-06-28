package cloudcode.krakenfutures.leadlag.monitor;

import java.util.ArrayDeque;
import java.util.Deque;

public class BtcVolumeMonitor {

    private static final long WINDOW_DURATION_MS = 300_000; // 5 minutes
    private static final long CURRENT_WINDOW_MS = 10_000; // 10 seconds

    private final Deque<VolumePoint> volumeWindow = new ArrayDeque<>();

    public synchronized void recordTrade(double volume, long timestampMs) {
        volumeWindow.addLast(new VolumePoint(volume, timestampMs));
        cleanWindow(timestampMs);
    }

    /**
     * Checks if the volume in the last 10 seconds is >= multiplier * avg 10-sec volume over 5 min.
     */
    public synchronized boolean isVolumeSpikeDetected(double multiplier) {
        if (volumeWindow.isEmpty()) {
            return false;
        }

        long now = System.currentTimeMillis();
        double recentVolume = 0;
        for (VolumePoint vp : volumeWindow) {
            if (vp.timestamp >= now - CURRENT_WINDOW_MS) {
                recentVolume += vp.volume;
            }
        }

        double totalVolume = 0;
        for (VolumePoint vp : volumeWindow) {
            totalVolume += vp.volume;
        }

        // Number of full 10-sec windows in 5 minutes = 30
        double avgVolumePerWindow = totalVolume / 30.0;
        if (avgVolumePerWindow == 0) return false;

        return recentVolume >= multiplier * avgVolumePerWindow;
    }

    private void cleanWindow(long currentTimestamp) {
        long cutoff = currentTimestamp - WINDOW_DURATION_MS;
        while (!volumeWindow.isEmpty() && volumeWindow.peekFirst().timestamp < cutoff) {
            volumeWindow.pollFirst();
        }
    }

    private static class VolumePoint {
        final double volume;
        final long timestamp;

        VolumePoint(double volume, long timestamp) {
            this.volume = volume;
            this.timestamp = timestamp;
        }
    }
}