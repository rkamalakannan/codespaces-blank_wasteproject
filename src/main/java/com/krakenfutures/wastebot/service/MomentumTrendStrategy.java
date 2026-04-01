package com.krakenfutures.wastebot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.OpenPosition;
import org.knowm.xchange.instrument.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.krakenfutures.wastebot.service.KrakenOHLCService.OHLCData;
import com.krakenfutures.wastebot.weblayer.KrakenFutureConfiguration;

/**
 * Momentum-based trend-following strategy using EMA (Exponential Moving Average).
 * 
 * Entry signals:
 * - LONG: Price crosses above EMA (uptrend)
 * - SHORT: Price crosses below EMA (downtrend)
 * 
 * Exit signals:
 * - Stop-loss: ATR-based dynamic stop (default 2x ATR)
 * - Take-profit: Risk/reward ratio (default 2:1)
 * 
 * This strategy is more reliable than spot-futures arbitrage for directional trading.
 */
@Service
public class MomentumTrendStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MomentumTrendStrategy.class);

    @Autowired
    private KrakenOHLCService ohlcService;

    @Autowired
    private KrakenFutureConfiguration krakenConfiguration;

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

    /**
     * Analyze an asset and determine if a trade signal exists.
     * 
     * @param asset the asset symbol (e.g., "BTC")
     * @return TradeSignal with direction and prices, or null if no signal
     */
    public TradeSignal analyzeAsset(String asset) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        Instrument instrument = pair;

        logger.info("[STRATEGY] {} - Analyzing with EMA({}) on {} candles...", asset, emaPeriod, ohlcInterval);

        // Get OHLC data for the past year (or available data)
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, KrakenOHLCService.Interval.fromString(ohlcInterval));

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

            logger.info("[STRATEGY] {} - LONG SIGNAL: price crossed above EMA. Entry: {}, Stop: {}, Target: {}",
                    asset, currentPrice, stopLoss, takeProfit);

            return new TradeSignal(asset, "LONG", currentPrice, stopLoss, takeProfit, ema, atr);
        }

        // SHORT signal: price crosses below EMA (bearish crossover)
        if (!currentAboveEMA && previousAboveEMA) {
            BigDecimal stopLoss = currentPrice.add(atr.multiply(BigDecimal.valueOf(atrStopMultiplier)));
            BigDecimal riskAmount = stopLoss.subtract(currentPrice);
            BigDecimal takeProfit = currentPrice.subtract(riskAmount.multiply(BigDecimal.valueOf(riskRewardRatio)));

            logger.info("[STRATEGY] {} - SHORT SIGNAL: price crossed below EMA. Entry: {}, Stop: {}, Target: {}",
                    asset, currentPrice, stopLoss, takeProfit);

            return new TradeSignal(asset, "SHORT", currentPrice, stopLoss, takeProfit, ema, atr);
        }

        logger.info("[STRATEGY] {} - No signal. Price {} EMA (no crossover).",
                asset, currentAboveEMA ? "above" : "below");
        return null;
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
     * Trade signal result
     */
    public static class TradeSignal {
        private final String asset;
        private final String direction;  // "LONG" or "SHORT"
        private final BigDecimal entryPrice;
        private final BigDecimal stopLoss;
        private final BigDecimal takeProfit;
        private final BigDecimal ema;
        private final BigDecimal atr;

        public TradeSignal(String asset, String direction, BigDecimal entryPrice,
                          BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal ema, BigDecimal atr) {
            this.asset = asset;
            this.direction = direction;
            this.entryPrice = entryPrice;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.ema = ema;
            this.atr = atr;
        }

        public String getAsset() { return asset; }
        public String getDirection() { return direction; }
        public BigDecimal getEntryPrice() { return entryPrice; }
        public BigDecimal getStopLoss() { return stopLoss; }
        public BigDecimal getTakeProfit() { return takeProfit; }
        public BigDecimal getEma() { return ema; }
        public BigDecimal getAtr() { return atr; }

        @Override
        public String toString() {
            return String.format("%s %s @ %s (SL: %s, TP: %s, EMA: %s, ATR: %s)",
                    direction, asset, entryPrice, stopLoss, takeProfit, ema, atr);
        }
    }
}
