package com.krakenfutures.wastebot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.krakenfutures.wastebot.service.KrakenOHLCService.OHLCData;
import com.krakenfutures.wastebot.service.TechnicalIndicatorService.BollingerResult;
import com.krakenfutures.wastebot.service.TechnicalIndicatorService.MACDResult;
import com.krakenfutures.wastebot.service.TechnicalIndicatorService.RSIResult;
import com.krakenfutures.wastebot.service.VolumeIndicatorService.OBVResult;
import com.krakenfutures.wastebot.service.VolumeIndicatorService.VWAPResult;
import com.krakenfutures.wastebot.weblayer.KrakenFutureConfiguration;

/**
 * Momentum-based trend-following strategy using EMA (Exponential Moving Average)
 * with RSI, MACD, Bollinger Bands, OBV, and VWAP confirmation filters.
 *
 * Entry signals:
 * - LONG: Price crosses above EMA (uptrend), confirmed by indicator filters
 * - SHORT: Price crosses below EMA (downtrend), confirmed by indicator filters
 *
 * Exit signals:
 * - Stop-loss: ATR-based dynamic stop (default 2x ATR)
 * - Take-profit: Risk/reward ratio (default 2:1)
 *
 * Each confirming indicator increases the confidence score (0-100).
 * A trade is only executed when confidence >= min-confidence threshold.
 * Falls back to EMA+ATR only behavior if indicator calculations fail.
 */
@Service
public class MomentumTrendStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MomentumTrendStrategy.class);

    private static final int BASE_CONFIDENCE = 40;

    @Autowired
    private KrakenOHLCService ohlcService;

    @Autowired
    private KrakenFutureConfiguration krakenConfiguration;

    @Autowired
    private TechnicalIndicatorService technicalIndicatorService;

    @Autowired
    private VolumeIndicatorService volumeIndicatorService;

    // EMA period for trend identification (default: 20 periods)
    @Value("${trading.strategy.ema-period:20}")
    private int emaPeriod;

    // ATR period for volatility measurement (default: 14 periods)
    @Value("${trading.strategy.atr-period:14}")
    private int atrPeriod;

    // ATR multiplier for stop-loss distance (default: 2.0)
    @Value("${trading.strategy.atr-stop-multiplier:2.0}")
    private double atrStopMultiplier;

    // Risk/reward ratio for take-profit (default: 2.0 = 2:1 reward:risk)
    @Value("${trading.strategy.risk-reward-ratio:2.0}")
    private double riskRewardRatio;

    // OHLC interval for analysis (default: 1h)
    @Value("${trading.strategy.ohlc-interval:1h}")
    private String ohlcInterval;

    // Minimum confidence score to execute a trade (default: 60)
    @Value("${trading.strategy.min-confidence:60}")
    private int minConfidence;

    /**
     * Analyze an asset and determine if a trade signal exists.
     *
     * @param asset the asset symbol (e.g., "BTC")
     * @return TradeSignal with direction, prices, and confidence, or null if no signal
     */
    public TradeSignal analyzeAsset(String asset) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        Instrument instrument = pair;

        logger.info("[STRATEGY] {} - Analyzing with EMA({}) on {} candles...", asset, emaPeriod, ohlcInterval);

        // Get OHLC data for the past year (or available data)
        KrakenOHLCService.Interval interval = KrakenOHLCService.Interval.fromString(ohlcInterval);
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, interval);

        if (ohlcData.size() < emaPeriod + atrPeriod) {
            logger.warn("[STRATEGY] {} - Insufficient OHLC data ({} candles, need at least {}). Skipping.",
                    asset, ohlcData.size(), emaPeriod + atrPeriod);
            return null;
        }

        // Calculate EMA
        BigDecimal ema = calculateEMA(ohlcData, emaPeriod);
        BigDecimal currentPrice = ohlcData.get(ohlcData.size() - 1).getClose();
        BigDecimal previousPrice = ohlcData.get(ohlcData.size() - 2).getClose();

        // Calculate ATR for dynamic stop-loss
        BigDecimal atr = calculateATR(ohlcData, atrPeriod);

        logger.info("[STRATEGY] {} - Current price: {}, EMA({}): {}, ATR({}): {}",
                asset, currentPrice, emaPeriod, ema, atrPeriod, atr);

        // Check for crossover signals
        boolean currentAboveEMA = currentPrice.compareTo(ema) > 0;
        boolean previousAboveEMA = previousPrice.compareTo(ema) > 0;

        // LONG signal: price crosses above EMA (bullish crossover)
        if (currentAboveEMA && !previousAboveEMA) {
            BigDecimal stopLoss = currentPrice.subtract(atr.multiply(BigDecimal.valueOf(atrStopMultiplier)));
            BigDecimal riskAmount = currentPrice.subtract(stopLoss);
            BigDecimal takeProfit = currentPrice.add(riskAmount.multiply(BigDecimal.valueOf(riskRewardRatio)));

            TradeSignal signal = buildSignalWithConfidence(
                    asset, "LONG", currentPrice, stopLoss, takeProfit, ema, atr, ohlcData, interval);

            if (signal.getConfidence() < minConfidence) {
                logger.info("[STRATEGY] {} - LONG signal rejected: confidence {} < min {}",
                        asset, signal.getConfidence(), minConfidence);
                return null;
            }

            logger.info("[STRATEGY] {} - LONG SIGNAL (confidence: {}): Entry: {}, Stop: {}, Target: {}",
                    asset, signal.getConfidence(), currentPrice, stopLoss, takeProfit);
            return signal;
        }

        // SHORT signal: price crosses below EMA (bearish crossover)
        if (!currentAboveEMA && previousAboveEMA) {
            BigDecimal stopLoss = currentPrice.add(atr.multiply(BigDecimal.valueOf(atrStopMultiplier)));
            BigDecimal riskAmount = stopLoss.subtract(currentPrice);
            BigDecimal takeProfit = currentPrice.subtract(riskAmount.multiply(BigDecimal.valueOf(riskRewardRatio)));

            TradeSignal signal = buildSignalWithConfidence(
                    asset, "SHORT", currentPrice, stopLoss, takeProfit, ema, atr, ohlcData, interval);

            if (signal.getConfidence() < minConfidence) {
                logger.info("[STRATEGY] {} - SHORT signal rejected: confidence {} < min {}",
                        asset, signal.getConfidence(), minConfidence);
                return null;
            }

            logger.info("[STRATEGY] {} - SHORT SIGNAL (confidence: {}): Entry: {}, Stop: {}, Target: {}",
                    asset, signal.getConfidence(), currentPrice, stopLoss, takeProfit);
            return signal;
        }

        logger.info("[STRATEGY] {} - No signal. Price {} EMA (no crossover).",
                asset, currentAboveEMA ? "above" : "below");
        return null;
    }

    /**
     * Build a TradeSignal with indicator-based confidence scoring.
     * Falls back to base confidence if any indicator fails to calculate.
     */
    private TradeSignal buildSignalWithConfidence(String asset, String direction,
                                                   BigDecimal entryPrice, BigDecimal stopLoss,
                                                   BigDecimal takeProfit, BigDecimal ema, BigDecimal atr,
                                                   List<OHLCData> ohlcData,
                                                   KrakenOHLCService.Interval interval) {
        int confidence = BASE_CONFIDENCE;
        BigDecimal rsiValue = null;
        BigDecimal macdHistogram = null;
        String bollingerPosition = null;
        String obvTrend = null;
        String vwapPosition = null;

        // --- RSI filter ---
        try {
            RSIResult rsiResult = technicalIndicatorService.calculateRSI(ohlcData, 14);
            rsiValue = rsiResult.getLatestRSI();

            if ("LONG".equals(direction)) {
                if (rsiValue.compareTo(BigDecimal.valueOf(70)) < 0) {
                    confidence += 10;
                    if (rsiValue.compareTo(BigDecimal.valueOf(50)) > 0) {
                        confidence += 5;
                    }
                }
            } else {
                if (rsiValue.compareTo(BigDecimal.valueOf(30)) > 0) {
                    confidence += 10;
                    if (rsiValue.compareTo(BigDecimal.valueOf(50)) < 0) {
                        confidence += 5;
                    }
                }
            }
            logger.info("[STRATEGY] {} - RSI filter: {} (confidence: {})", asset, rsiValue, confidence);
        } catch (Exception e) {
            logger.warn("[STRATEGY] {} - RSI calculation failed, skipping filter: {}", asset, e.getMessage());
        }

        // --- MACD filter ---
        try {
            MACDResult macdResult = technicalIndicatorService.calculateMACD(ohlcData, 12, 26, 9);
            macdHistogram = macdResult.getLatestHistogram();

            if ("LONG".equals(direction) && macdHistogram.compareTo(BigDecimal.ZERO) > 0) {
                confidence += 10;
            } else if ("SHORT".equals(direction) && macdHistogram.compareTo(BigDecimal.ZERO) < 0) {
                confidence += 10;
            }
            logger.info("[STRATEGY] {} - MACD filter: histogram={} (confidence: {})", asset, macdHistogram, confidence);
        } catch (Exception e) {
            logger.warn("[STRATEGY] {} - MACD calculation failed, skipping filter: {}", asset, e.getMessage());
        }

        // --- Bollinger Bands filter ---
        try {
            BollingerResult bollingerResult = technicalIndicatorService.calculateBollinger(ohlcData, 20, 2);
            bollingerPosition = bollingerResult.getPosition();

            if ("LONG".equals(direction)) {
                if (!"above_upper".equals(bollingerPosition)) {
                    confidence += 10;
                }
                if ("lower_half".equals(bollingerPosition) || "below_lower".equals(bollingerPosition)) {
                    confidence += 5;
                }
            } else {
                if (!"below_lower".equals(bollingerPosition)) {
                    confidence += 10;
                }
                if ("upper_half".equals(bollingerPosition) || "above_upper".equals(bollingerPosition)) {
                    confidence += 5;
                }
            }
            logger.info("[STRATEGY] {} - Bollinger filter: {} (confidence: {})", asset, bollingerPosition, confidence);
        } catch (Exception e) {
            logger.warn("[STRATEGY] {} - Bollinger calculation failed, skipping filter: {}", asset, e.getMessage());
        }

        // --- OBV filter ---
        try {
            OBVResult obvResult = volumeIndicatorService.calculateOBV(ohlcData);
            obvTrend = obvResult.getTrend();

            if ("LONG".equals(direction) && "rising".equals(obvTrend)) {
                confidence += 10;
            } else if ("SHORT".equals(direction) && "falling".equals(obvTrend)) {
                confidence += 10;
            }
            logger.info("[STRATEGY] {} - OBV filter: trend={} (confidence: {})", asset, obvTrend, confidence);
        } catch (Exception e) {
            logger.warn("[STRATEGY] {} - OBV calculation failed, skipping filter: {}", asset, e.getMessage());
        }

        // --- VWAP filter ---
        try {
            VWAPResult vwapResult = volumeIndicatorService.calculateVWAP(ohlcData);
            vwapPosition = vwapResult.getPosition();

            if ("LONG".equals(direction) && "above".equals(vwapPosition)) {
                confidence += 10;
            } else if ("SHORT".equals(direction) && "below".equals(vwapPosition)) {
                confidence += 10;
            }
            logger.info("[STRATEGY] {} - VWAP filter: position={} (confidence: {})", asset, vwapPosition, confidence);
        } catch (Exception e) {
            logger.warn("[STRATEGY] {} - VWAP calculation failed, skipping filter: {}", asset, e.getMessage());
        }

        confidence = Math.min(confidence, 100);

        return new TradeSignal(asset, direction, entryPrice, stopLoss, takeProfit, ema, atr,
                confidence, rsiValue, macdHistogram, bollingerPosition, obvTrend, vwapPosition);
    }

    /**
     * Calculate Exponential Moving Average (EMA)
     */
    private BigDecimal calculateEMA(List<OHLCData> ohlcData, int period) {
        if (ohlcData.size() < period) {
            throw new IllegalArgumentException("Insufficient data for EMA calculation");
        }

        // Start with SMA for the first period
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = ohlcData.size() - period; i < ohlcData.size(); i++) {
            sum = sum.add(ohlcData.get(i).getClose());
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);

        // Calculate EMA multiplier: 2 / (period + 1)
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));

        // Apply EMA formula for remaining data points
        for (int i = ohlcData.size() - period + 1; i < ohlcData.size(); i++) {
            BigDecimal close = ohlcData.get(i).getClose();
            // EMA = (Close - EMA_prev) * multiplier + EMA_prev
            ema = close.subtract(ema).multiply(multiplier).add(ema);
        }

        return ema;
    }

    /**
     * Calculate Average True Range (ATR) for volatility measurement
     */
    private BigDecimal calculateATR(List<OHLCData> ohlcData, int period) {
        if (ohlcData.size() < period + 1) {
            throw new IllegalArgumentException("Insufficient data for ATR calculation");
        }

        BigDecimal atrSum = BigDecimal.ZERO;
        int startIndex = ohlcData.size() - period;

        for (int i = startIndex; i < ohlcData.size(); i++) {
            OHLCData current = ohlcData.get(i);
            OHLCData previous = (i > 0) ? ohlcData.get(i - 1) : current;

            // True Range = max(high - low, abs(high - prevClose), abs(low - prevClose))
            BigDecimal highLow = current.getHigh().subtract(current.getLow());
            BigDecimal highPrevClose = current.getHigh().subtract(previous.getClose()).abs();
            BigDecimal lowPrevClose = current.getLow().subtract(previous.getClose()).abs();

            BigDecimal trueRange = highLow.max(highPrevClose).max(lowPrevClose);
            atrSum = atrSum.add(trueRange);
        }

        return atrSum.divide(BigDecimal.valueOf(period), 8, RoundingMode.HALF_UP);
    }

    /**
     * Trade signal result with indicator confirmation details
     */
    public static class TradeSignal {
        private final String asset;
        private final String direction;  // "LONG" or "SHORT"
        private final BigDecimal entryPrice;
        private final BigDecimal stopLoss;
        private final BigDecimal takeProfit;
        private final BigDecimal ema;
        private final BigDecimal atr;
        private final int confidence;
        private final BigDecimal rsiValue;
        private final BigDecimal macdHistogram;
        private final String bollingerPosition;
        private final String obvTrend;
        private final String vwapPosition;

        /**
         * Full constructor with all indicator fields.
         */
        public TradeSignal(String asset, String direction, BigDecimal entryPrice,
                           BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal ema, BigDecimal atr,
                           int confidence, BigDecimal rsiValue, BigDecimal macdHistogram,
                           String bollingerPosition, String obvTrend, String vwapPosition) {
            this.asset = asset;
            this.direction = direction;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.ema = ema;
            this.atr = atr;
            this.confidence = confidence;
            this.rsiValue = rsiValue;
            this.macdHistogram = macdHistogram;
            this.bollingerPosition = bollingerPosition;
            this.obvTrend = obvTrend;
            this.vwapPosition = vwapPosition;
        }

        /**
         * Backward-compatible constructor (no indicator fields).
         * Sets confidence to base level (40) and indicator fields to null.
         */
        public TradeSignal(String asset, String direction, BigDecimal entryPrice,
                           BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal ema, BigDecimal atr) {
            this(asset, direction, entryPrice, stopLoss, takeProfit, ema, atr,
                    40, null, null, null, null, null);
        }

        public String getAsset() { return asset; }
        public String getDirection() { return direction; }
        public BigDecimal getEntryPrice() { return entryPrice; }
        public BigDecimal getStopLoss() { return stopLoss; }
        public BigDecimal getTakeProfit() { return takeProfit; }
        public BigDecimal getEma() { return ema; }
        public BigDecimal getAtr() { return atr; }
        public int getConfidence() { return confidence; }
        public BigDecimal getRsiValue() { return rsiValue; }
        public BigDecimal getMacdHistogram() { return macdHistogram; }
        public String getBollingerPosition() { return bollingerPosition; }
        public String getObvTrend() { return obvTrend; }
        public String getVwapPosition() { return vwapPosition; }

        @Override
        public String toString() {
            return String.format("%s %s @ %s (SL: %s, TP: %s, EMA: %s, ATR: %s, confidence: %d)",
                    direction, asset, entryPrice, stopLoss, takeProfit, ema, atr, confidence);
        }
    }
}
