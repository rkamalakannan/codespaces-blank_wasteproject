package cloudcode.krakenfutures.leadlag.selector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CorrelationTracker {

    private final Map<String, Double> correlationCache = new HashMap<>();

    /**
     * Computes Pearson correlation between two price series.
     * Returns 0 if either series has fewer than 2 points or zero variance.
     */
    public double computeCorrelation(List<Double> seriesX, List<Double> seriesY) {
        if (seriesX == null || seriesY == null || seriesX.size() != seriesY.size() || seriesX.size() < 2) {
            return 0;
        }

        int n = seriesX.size();
        double sumX = 0, sumY = 0;
        for (int i = 0; i < n; i++) {
            sumX += seriesX.get(i);
            sumY += seriesY.get(i);
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double covXY = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = seriesX.get(i) - meanX;
            double dy = seriesY.get(i) - meanY;
            covXY += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }

        if (varX == 0 || varY == 0) return 0;
        return covXY / Math.sqrt(varX * varY);
    }

    public void updateCorrelation(String altcoin, double correlation) {
        correlationCache.put(altcoin, correlation);
    }

    public double getCorrelation(String altcoin) {
        return correlationCache.getOrDefault(altcoin, 0.0);
    }

    public Map<String, Double> getAllCorrelations() {
        return new HashMap<>(correlationCache);
    }
}