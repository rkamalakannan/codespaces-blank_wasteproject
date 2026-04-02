package com.krakenfutures.wastebot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.krakenfutures.wastebot.service.KrakenOHLCService.OHLCData;

/**
 * Volume-based technical indicator service providing OBV (On-Balance Volume)
 * and VWAP (Volume-Weighted Average Price) analysis.
 */
@Service
public class VolumeIndicatorService {

    private static final Logger logger = LoggerFactory.getLogger(VolumeIndicatorService.class);

    @Autowired
    private KrakenOHLCService ohlcService;

    /**
     * Calculate On-Balance Volume (OBV) from OHLC data.
     *
     * Rules:
     * - close > previous close: OBV += volume
     * - close < previous close: OBV -= volume
     * - close == previous close: OBV unchanged
     *
     * @param ohlcData list of OHLC candles (must have at least 1 candle)
     * @return OBVResult containing the full OBV series, latest value, and trend direction
     */
    public OBVResult calculateOBV(List<OHLCData> ohlcData) {
        if (ohlcData == null || ohlcData.isEmpty()) {
            throw new IllegalArgumentException("OHLC data must not be empty for OBV calculation");
        }

        List<BigDecimal> obvValues = new ArrayList<>();
        BigDecimal obv = BigDecimal.ZERO;
        obvValues.add(obv);

        for (int i = 1; i < ohlcData.size(); i++) {
            BigDecimal currentClose = ohlcData.get(i).getClose();
            BigDecimal previousClose = ohlcData.get(i - 1).getClose();
            BigDecimal volume = ohlcData.get(i).getVolume();

            int comparison = currentClose.compareTo(previousClose);
            if (comparison > 0) {
                obv = obv.add(volume);
            } else if (comparison < 0) {
                obv = obv.subtract(volume);
            }
            // If equal, OBV unchanged
            obvValues.add(obv);
        }

        String trend = determineTrend(obvValues);
        logger.info("[VOLUME] OBV latest: {}, trend: {}", obv, trend);

        return new OBVResult(obvValues, obv, trend);
    }

    /**
     * Get the latest OBV result for an asset.
     */
    public OBVResult getLatestOBV(CurrencyPair pair, KrakenOHLCService.Interval interval) throws IOException {
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);
        return calculateOBV(ohlcData);
    }

    /**
     * Calculate Volume-Weighted Average Price (VWAP) from OHLC data.
     *
     * VWAP per candle = cumulative (typical_price * volume) / cumulative volume
     * Typical price = (high + low + close) / 3
     *
     * @param ohlcData list of OHLC candles (must have at least 1 candle)
     * @return VWAPResult containing the full VWAP series, latest value, and price position
     */
    public VWAPResult calculateVWAP(List<OHLCData> ohlcData) {
        if (ohlcData == null || ohlcData.isEmpty()) {
            throw new IllegalArgumentException("OHLC data must not be empty for VWAP calculation");
        }

        List<BigDecimal> vwapValues = new ArrayList<>();
        BigDecimal cumulativeTypicalVolume = BigDecimal.ZERO;
        BigDecimal cumulativeVolume = BigDecimal.ZERO;

        for (OHLCData candle : ohlcData) {
            // Typical price = (high + low + close) / 3
            BigDecimal typicalPrice = candle.getHigh()
                    .add(candle.getLow())
                    .add(candle.getClose())
                    .divide(BigDecimal.valueOf(3), 8, RoundingMode.HALF_UP);

            cumulativeTypicalVolume = cumulativeTypicalVolume.add(
                    typicalPrice.multiply(candle.getVolume()));
            cumulativeVolume = cumulativeVolume.add(candle.getVolume());

            BigDecimal vwap;
            if (cumulativeVolume.compareTo(BigDecimal.ZERO) == 0) {
                vwap = typicalPrice;
            } else {
                vwap = cumulativeTypicalVolume.divide(cumulativeVolume, 8, RoundingMode.HALF_UP);
            }
            vwapValues.add(vwap);
        }

        BigDecimal latestVWAP = vwapValues.get(vwapValues.size() - 1);
        BigDecimal currentPrice = ohlcData.get(ohlcData.size() - 1).getClose();
        String position = currentPrice.compareTo(latestVWAP) > 0 ? "above" : "below";

        logger.info("[VOLUME] VWAP latest: {}, price: {}, position: {}", latestVWAP, currentPrice, position);

        return new VWAPResult(vwapValues, latestVWAP, position);
    }

    /**
     * Get the latest VWAP result for an asset.
     */
    public VWAPResult getLatestVWAP(CurrencyPair pair, KrakenOHLCService.Interval interval) throws IOException {
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);
        return calculateVWAP(ohlcData);
    }

    /**
     * Detect OBV divergence: price making new highs but OBV not confirming.
     *
     * Looks at the most recent window (last 20 candles) and compares:
     * - Whether price made a new high
     * - Whether OBV confirmed with a corresponding new high
     *
     * @param ohlcData list of OHLC candles
     * @param obvResult previously calculated OBV result
     * @return VolumeSignal with divergence detection and VWAP bias
     */
    public VolumeSignal getVolumeSignal(List<OHLCData> ohlcData, OBVResult obvResult) {
        VWAPResult vwapResult = calculateVWAP(ohlcData);
        return analyzeSignals(ohlcData, obvResult, vwapResult);
    }

    /**
     * Full volume signal analysis combining OBV and VWAP.
     */
    public VolumeSignal analyzeSignals(List<OHLCData> ohlcData, OBVResult obvResult, VWAPResult vwapResult) {
        int windowSize = Math.min(20, ohlcData.size());

        // Find highest close and highest OBV in the last windowSize candles
        BigDecimal highestClose = BigDecimal.ZERO;
        BigDecimal highestOBV = null;
        int priceHighIndex = -1;
        int obvHighIndex = -1;

        int startIdx = ohlcData.size() - windowSize;
        for (int i = startIdx; i < ohlcData.size(); i++) {
            BigDecimal close = ohlcData.get(i).getClose();
            BigDecimal obv = obvResult.getObvValues().get(i);

            if (close.compareTo(highestClose) > 0) {
                highestClose = close;
                priceHighIndex = i;
            }
            if (highestOBV == null || obv.compareTo(highestOBV) > 0) {
                highestOBV = obv;
                obvHighIndex = i;
            }
        }

        // Divergence: price at/near new high but OBV did not confirm
        boolean bearishDivergence = (priceHighIndex == ohlcData.size() - 1 || priceHighIndex == ohlcData.size() - 2)
                && obvHighIndex < priceHighIndex - 3;

        String obvDivergence = bearishDivergence ? "bearish" : "none";
        String vwapBias = vwapResult.getPosition().equals("above") ? "bullish" : "bearish";

        logger.info("[VOLUME] Signal - OBV divergence: {}, VWAP bias: {}", obvDivergence, vwapBias);

        return new VolumeSignal(obvResult, vwapResult, obvDivergence, vwapBias);
    }

    /**
     * Get comprehensive volume signal for an asset.
     */
    public VolumeSignal getVolumeSignal(String asset, KrakenOHLCService.Interval interval) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);

        if (ohlcData.isEmpty()) {
            throw new IllegalArgumentException("No OHLC data available for " + asset);
        }

        OBVResult obvResult = calculateOBV(ohlcData);
        VWAPResult vwapResult = calculateVWAP(ohlcData);
        return analyzeSignals(ohlcData, obvResult, vwapResult);
    }

    /**
     * Determine trend direction from a series of values.
     * Compares the last 5 values (or fewer if not enough data).
     */
    private String determineTrend(List<BigDecimal> values) {
        if (values.size() < 2) {
            return "flat";
        }

        int lookback = Math.min(5, values.size());
        BigDecimal first = values.get(values.size() - lookback);
        BigDecimal last = values.get(values.size() - 1);

        int comparison = last.compareTo(first);
        if (comparison > 0) {
            return "rising";
        } else if (comparison < 0) {
            return "falling";
        }
        return "flat";
    }

    /**
     * Result of OBV (On-Balance Volume) calculation.
     */
    public static class OBVResult {
        private final List<BigDecimal> obvValues;
        private final BigDecimal latestOBV;
        private final String trend; // "rising", "falling", "flat"

        public OBVResult(List<BigDecimal> obvValues, BigDecimal latestOBV, String trend) {
            this.obvValues = obvValues;
            this.latestOBV = latestOBV;
            this.trend = trend;
        }

        public List<BigDecimal> getObvValues() { return obvValues; }
        public BigDecimal getLatestOBV() { return latestOBV; }
        public String getTrend() { return trend; }

        @Override
        public String toString() {
            return String.format("OBV[latest=%s, trend=%s]", latestOBV, trend);
        }
    }

    /**
     * Result of VWAP (Volume-Weighted Average Price) calculation.
     */
    public static class VWAPResult {
        private final List<BigDecimal> vwapValues;
        private final BigDecimal latestVWAP;
        private final String position; // "above" or "below"

        public VWAPResult(List<BigDecimal> vwapValues, BigDecimal latestVWAP, String position) {
            this.vwapValues = vwapValues;
            this.latestVWAP = latestVWAP;
            this.position = position;
        }

        public List<BigDecimal> getVwapValues() { return vwapValues; }
        public BigDecimal getLatestVWAP() { return latestVWAP; }
        public String getPosition() { return position; }

        @Override
        public String toString() {
            return String.format("VWAP[latest=%s, position=%s]", latestVWAP, position);
        }
    }

    /**
     * Combined volume signal with OBV and VWAP analysis.
     */
    public static class VolumeSignal {
        private final OBVResult obvResult;
        private final VWAPResult vwapResult;
        private final String obvDivergence; // "bearish" or "none"
        private final String vwapBias;      // "bullish" or "bearish"

        public VolumeSignal(OBVResult obvResult, VWAPResult vwapResult,
                            String obvDivergence, String vwapBias) {
            this.obvResult = obvResult;
            this.vwapResult = vwapResult;
            this.obvDivergence = obvDivergence;
            this.vwapBias = vwapBias;
        }

        public OBVResult getObvResult() { return obvResult; }
        public VWAPResult getVwapResult() { return vwapResult; }
        public String getObvDivergence() { return obvDivergence; }
        public String getVwapBias() { return vwapBias; }

        @Override
        public String toString() {
            return String.format("VolumeSignal[OBV=%s, VWAP=%s, divergence=%s, bias=%s]",
                    obvResult, vwapResult, obvDivergence, vwapBias);
        }
    }
}
