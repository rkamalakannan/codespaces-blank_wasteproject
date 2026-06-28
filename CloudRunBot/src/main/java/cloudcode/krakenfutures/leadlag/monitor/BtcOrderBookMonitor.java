package cloudcode.krakenfutures.leadlag.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BtcOrderBookMonitor {

    private final Map<Double, Double> bids = new TreeMap<>(Comparator.reverseOrder());
    private final Map<Double, Double> asks = new TreeMap<>();

    /**
     * Updates the order book with new bid/ask data.
     * 
     * @param bidLevels list of [price, size] tuples for bids.
     * @param askLevels list of [price, size] tuples for asks.
     */
    public synchronized void updateOrderBook(List<double[]> bidLevels, List<double[]> askLevels) {
        if (bidLevels != null) {
            for (double[] level : bidLevels) {
                if (level.length >= 2 && level[1] > 0) {
                    bids.put(level[0], level[1]);
                } else {
                    bids.remove(level[0]);
                }
            }
        }
        if (askLevels != null) {
            for (double[] level : askLevels) {
                if (level.length >= 2 && level[1] > 0) {
                    asks.put(level[0], level[1]);
                } else {
                    asks.remove(level[0]);
                }
            }
        }
    }

    /**
     * Computes the order book imbalance for the top N levels.
     * Imbalance = (bidVol - askVol) / (bidVol + askVol).
     * Returns 0 if no liquidity.
     */
    public synchronized double computeImbalance(int topNLevels) {
        double bidVol = 0;
        double askVol = 0;
        int count = 0;

        for (Map.Entry<Double, Double> entry : bids.entrySet()) {
            if (count >= topNLevels) break;
            bidVol += entry.getValue();
            count++;
        }

        count = 0;
        for (Map.Entry<Double, Double> entry : asks.entrySet()) {
            if (count >= topNLevels) break;
            askVol += entry.getValue();
            count++;
        }

        double totalVol = bidVol + askVol;
        if (totalVol == 0) return 0;
        return (bidVol - askVol) / totalVol;
    }

}