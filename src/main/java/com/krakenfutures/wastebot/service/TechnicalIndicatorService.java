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
 * Technical indicator service providing RSI, MACD, and Bollinger Bands analysis.
 */
@Service
public class TechnicalIndicatorService {

    private static final Logger logger = LoggerFactory.getLogger(TechnicalIndicatorService.class);

    @Autowired
    private KrakenOHLCService ohlcService;

    /**
     * Calculate RSI (Relative Strength Index) from OHLC data.
     *
     * RSI = 100 - (100 / (1 + RS))
     * RS = Average Gain / Average Loss over the period
     *
     * @param ohlcData list of OHLC candles (must have at least period + 1 candles)
     * @param period   RSI lookback period (typically 14)
     * @return RSIResult containing the latest RSI value and series
     */
    public RSIResult calculateRSI(List<OHLCData> ohlcData, int period) {
        if (ohlcData == null || ohlcData.size() < period + 1) {
            throw new IllegalArgumentException("Insufficient OHLC data for RSI calculation (need at least " + (period + 1) + " candles)");
        }

        List<BigDecimal> rsiValues = new ArrayList<>();
        List<BigDecimal> gains = new ArrayList<>();
        List<BigDecimal> losses = new ArrayList<>();

        // Calculate price changes
        for (int i = 1; i < ohlcData.size(); i++) {
            BigDecimal change = ohlcData.get(i).getClose().subtract(ohlcData.get(i - 1).getClose());
            gains.add(change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO);
            losses.add(change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO);
        }

        // Calculate initial average gain and loss (SMA for first period)
        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            avgGain = avgGain.add(gains.get(i));
            avgLoss = avgLoss.add(losses.get(i));
        }
        avgGain = avgGain.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // First RSI value
        BigDecimal rsi = calculateRsiFromAverages(avgGain, avgLoss);
        rsiValues.add(rsi);

        // Subsequent RSI values using smoothed averages
        for (int i = period; i < gains.size(); i++) {
            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                    .add(gains.get(i))
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                    .add(losses.get(i))
                    .divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

            rsi = calculateRsiFromAverages(avgGain, avgLoss);
            rsiValues.add(rsi);
        }

        BigDecimal latestRSI = rsiValues.get(rsiValues.size() - 1);
        logger.info("[INDICATOR] RSI({}) latest: {}", period, latestRSI);

        return new RSIResult(rsiValues, latestRSI);
    }

    private BigDecimal calculateRsiFromAverages(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_UP));
    }

    /**
     * Calculate MACD (Moving Average Convergence Divergence) from OHLC data.
     *
     * MACD Line = EMA(12) - EMA(26)
     * Signal Line = EMA(9) of MACD Line
     * Histogram = MACD Line - Signal Line
     *
     * @param ohlcData      list of OHLC candles
     * @param fastPeriod    fast EMA period (typically 12)
     * @param slowPeriod    slow EMA period (typically 26)
     * @param signalPeriod  signal line EMA period (typically 9)
     * @return MACDResult containing MACD line, signal line, and histogram values
     */
    public MACDResult calculateMACD(List<OHLCData> ohlcData, int fastPeriod, int slowPeriod, int signalPeriod) {
        if (ohlcData == null || ohlcData.size() < slowPeriod + signalPeriod) {
            throw new IllegalArgumentException("Insufficient OHLC data for MACD calculation (need at least " + (slowPeriod + signalPeriod) + " candles)");
        }

        // Calculate fast and slow EMAs
        List<BigDecimal> fastEMA = calculateEMASeries(ohlcData, fastPeriod);
        List<BigDecimal> slowEMA = calculateEMASeries(ohlcData, slowPeriod);

        // MACD line = fast EMA - slow EMA (aligned to slow EMA start)
        int offset = slowPeriod - fastPeriod;
        List<BigDecimal> macdLine = new ArrayList<>();
        for (int i = 0; i < slowEMA.size(); i++) {
            BigDecimal fast = fastEMA.get(i + offset);
            BigDecimal slow = slowEMA.get(i);
            macdLine.add(fast.subtract(slow));
        }

        // Signal line = EMA of MACD line
        List<BigDecimal> signalLine = calculateEMAFromSeries(macdLine, signalPeriod);

        // Histogram = MACD line - signal line (aligned to signal line start)
        int histOffset = macdLine.size() - signalLine.size();
        List<BigDecimal> histogram = new ArrayList<>();
        for (int i = 0; i < signalLine.size(); i++) {
            histogram.add(macdLine.get(i + histOffset).subtract(signalLine.get(i)));
        }

        BigDecimal latestMACD = macdLine.get(macdLine.size() - 1);
        BigDecimal latestSignal = signalLine.get(signalLine.size() - 1);
        BigDecimal latestHistogram = histogram.get(histogram.size() - 1);

        logger.info("[INDICATOR] MACD({},{},{}) - Line: {}, Signal: {}, Histogram: {}",
                fastPeriod, slowPeriod, signalPeriod, latestMACD, latestSignal, latestHistogram);

        return new MACDResult(macdLine, signalLine, histogram, latestMACD, latestSignal, latestHistogram);
    }

    /**
     * Calculate Bollinger Bands from OHLC data.
     *
     * Middle Band = SMA(period)
     * Upper Band = Middle Band + (stdDev * multiplier)
     * Lower Band = Middle Band - (stdDev * multiplier)
     *
     * @param ohlcData   list of OHLC candles
     * @param period     SMA period (typically 20)
     * @param stdDevMult standard deviation multiplier (typically 2)
     * @return BollingerResult containing upper, middle, lower bands and %B position
     */
    public BollingerResult calculateBollinger(List<OHLCData> ohlcData, int period, int stdDevMult) {
        if (ohlcData == null || ohlcData.size() < period) {
            throw new IllegalArgumentException("Insufficient OHLC data for Bollinger Bands calculation (need at least " + period + " candles)");
        }

        List<BigDecimal> upperBand = new ArrayList<>();
        List<BigDecimal> middleBand = new ArrayList<>();
        List<BigDecimal> lowerBand = new ArrayList<>();
        List<BigDecimal> percentB = new ArrayList<>();

        for (int i = period - 1; i < ohlcData.size(); i++) {
            // Calculate SMA for the window
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                sum = sum.add(ohlcData.get(j).getClose());
            }
            BigDecimal sma = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

            // Calculate standard deviation
            BigDecimal variance = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                BigDecimal diff = ohlcData.get(j).getClose().subtract(sma);
                variance = variance.add(diff.multiply(diff));
            }
            variance = variance.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

            BigDecimal upper = sma.add(stdDev.multiply(BigDecimal.valueOf(stdDevMult)));
            BigDecimal lower = sma.subtract(stdDev.multiply(BigDecimal.valueOf(stdDevMult)));

            middleBand.add(sma);
            upperBand.add(upper);
            lowerBand.add(lower);

            // %B = (price - lower) / (upper - lower)
            BigDecimal currentPrice = ohlcData.get(i).getClose();
            BigDecimal bandWidth = upper.subtract(lower);
            BigDecimal pb;
            if (bandWidth.compareTo(BigDecimal.ZERO) == 0) {
                pb = BigDecimal.valueOf(0.5);
            } else {
                pb = currentPrice.subtract(lower).divide(bandWidth, 8, RoundingMode.HALF_UP);
            }
            percentB.add(pb);
        }

        BigDecimal latestUpper = upperBand.get(upperBand.size() - 1);
        BigDecimal latestMiddle = middleBand.get(middleBand.size() - 1);
        BigDecimal latestLower = lowerBand.get(lowerBand.size() - 1);
        BigDecimal latestPercentB = percentB.get(percentB.size() - 1);
        BigDecimal currentPrice = ohlcData.get(ohlcData.size() - 1).getClose();

        String position;
        if (currentPrice.compareTo(latestUpper) >= 0) {
            position = "above_upper";
        } else if (currentPrice.compareTo(latestLower) <= 0) {
            position = "below_lower";
        } else if (currentPrice.compareTo(latestMiddle) >= 0) {
            position = "upper_half";
        } else {
            position = "lower_half";
        }

        logger.info("[INDICATOR] Bollinger({},{}) - Upper: {}, Mid: {}, Lower: {}, %B: {}, position: {}",
                period, stdDevMult, latestUpper, latestMiddle, latestLower, latestPercentB, position);

        return new BollingerResult(upperBand, middleBand, lowerBand, percentB,
                latestUpper, latestMiddle, latestLower, latestPercentB, position);
    }

    /**
     * Get RSI for an asset.
     */
    public RSIResult getRSI(String asset, int period, KrakenOHLCService.Interval interval) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);
        return calculateRSI(ohlcData, period);
    }

    /**
     * Get MACD for an asset.
     */
    public MACDResult getMACD(String asset, int fastPeriod, int slowPeriod, int signalPeriod,
                              KrakenOHLCService.Interval interval) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);
        return calculateMACD(ohlcData, fastPeriod, slowPeriod, signalPeriod);
    }

    /**
     * Get Bollinger Bands for an asset.
     */
    public BollingerResult getBollinger(String asset, int period, int stdDevMult,
                                        KrakenOHLCService.Interval interval) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);
        return calculateBollinger(ohlcData, period, stdDevMult);
    }

    /**
     * Calculate EMA series for the full dataset.
     */
    private List<BigDecimal> calculateEMASeries(List<OHLCData> ohlcData, int period) {
        List<BigDecimal> closes = new ArrayList<>();
        for (OHLCData d : ohlcData) {
            closes.add(d.getClose());
        }
        return calculateEMAFromSeries(closes, period);
    }

    /**
     * Calculate EMA series from a list of values.
     */
    private List<BigDecimal> calculateEMAFromSeries(List<BigDecimal> values, int period) {
        List<BigDecimal> emaSeries = new ArrayList<>();
        if (values.size() < period) {
            return emaSeries;
        }

        // Start with SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(values.get(i));
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
        emaSeries.add(ema);

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        for (int i = period; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(multiplier).add(ema);
            emaSeries.add(ema);
        }

        return emaSeries;
    }

    // ==================== Result classes ====================

    public static class RSIResult {
        private final List<BigDecimal> rsiValues;
        private final BigDecimal latestRSI;

        public RSIResult(List<BigDecimal> rsiValues, BigDecimal latestRSI) {
            this.rsiValues = rsiValues;
            this.latestRSI = latestRSI;
        }

        public List<BigDecimal> getRsiValues() { return rsiValues; }
        public BigDecimal getLatestRSI() { return latestRSI; }

        @Override
        public String toString() {
            return String.format("RSI[latest=%s]", latestRSI);
        }
    }

    public static class MACDResult {
        private final List<BigDecimal> macdLine;
        private final List<BigDecimal> signalLine;
        private final List<BigDecimal> histogram;
        private final BigDecimal latestMACD;
        private final BigDecimal latestSignal;
        private final BigDecimal latestHistogram;

        public MACDResult(List<BigDecimal> macdLine, List<BigDecimal> signalLine,
                          List<BigDecimal> histogram, BigDecimal latestMACD,
                          BigDecimal latestSignal, BigDecimal latestHistogram) {
            this.macdLine = macdLine;
            this.signalLine = signalLine;
            this.histogram = histogram;
            this.latestMACD = latestMACD;
            this.latestSignal = latestSignal;
            this.latestHistogram = latestHistogram;
        }

        public List<BigDecimal> getMacdLine() { return macdLine; }
        public List<BigDecimal> getSignalLine() { return signalLine; }
        public List<BigDecimal> getHistogram() { return histogram; }
        public BigDecimal getLatestMACD() { return latestMACD; }
        public BigDecimal getLatestSignal() { return latestSignal; }
        public BigDecimal getLatestHistogram() { return latestHistogram; }

        @Override
        public String toString() {
            return String.format("MACD[line=%s, signal=%s, histogram=%s]", latestMACD, latestSignal, latestHistogram);
        }
    }

    public static class BollingerResult {
        private final List<BigDecimal> upperBand;
        private final List<BigDecimal> middleBand;
        private final List<BigDecimal> lowerBand;
        private final List<BigDecimal> percentB;
        private final BigDecimal latestUpper;
        private final BigDecimal latestMiddle;
        private final BigDecimal latestLower;
        private final BigDecimal latestPercentB;
        private final String position;

        public BollingerResult(List<BigDecimal> upperBand, List<BigDecimal> middleBand,
                               List<BigDecimal> lowerBand, List<BigDecimal> percentB,
                               BigDecimal latestUpper, BigDecimal latestMiddle,
                               BigDecimal latestLower, BigDecimal latestPercentB,
                               String position) {
            this.upperBand = upperBand;
            this.middleBand = middleBand;
            this.lowerBand = lowerBand;
            this.percentB = percentB;
            this.latestUpper = latestUpper;
            this.latestMiddle = latestMiddle;
            this.latestLower = latestLower;
            this.latestPercentB = latestPercentB;
            this.position = position;
        }

        public List<BigDecimal> getUpperBand() { return upperBand; }
        public List<BigDecimal> getMiddleBand() { return middleBand; }
        public List<BigDecimal> getLowerBand() { return lowerBand; }
        public List<BigDecimal> getPercentB() { return percentB; }
        public BigDecimal getLatestUpper() { return latestUpper; }
        public BigDecimal getLatestMiddle() { return latestMiddle; }
        public BigDecimal getLatestLower() { return latestLower; }
        public BigDecimal getLatestPercentB() { return latestPercentB; }
        public String getPosition() { return position; }

        @Override
        public String toString() {
            return String.format("Bollinger[upper=%s, mid=%s, lower=%s, %%B=%s, pos=%s]",
                    latestUpper, latestMiddle, latestLower, latestPercentB, position);
        }
    }
}
