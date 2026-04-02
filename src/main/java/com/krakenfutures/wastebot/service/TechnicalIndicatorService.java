package com.krakenfutures.wastebot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.knowm.xchange.currency.CurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import com.krakenfutures.wastebot.service.KrakenOHLCService.OHLCData;

@Service
public class TechnicalIndicatorService {

    private static final Logger logger = LoggerFactory.getLogger(TechnicalIndicatorService.class);

    @Autowired
    private KrakenOHLCService ohlcService;

    @Value("${trading.indicator.rsi-period:14}")
    private int defaultRsiPeriod;

    @Value("${trading.indicator.macd-short-period:12}")
    private int defaultMacdShortPeriod;

    @Value("${trading.indicator.macd-long-period:26}")
    private int defaultMacdLongPeriod;

    @Value("${trading.indicator.macd-signal-period:9}")
    private int defaultMacdSignalPeriod;

    @Value("${trading.indicator.bb-period:20}")
    private int defaultBbPeriod;

    @Value("${trading.indicator.bb-std-dev:2.0}")
    private double defaultBbStdDev;

    @Value("${trading.strategy.ohlc-interval:1h}")
    private String ohlcInterval;

    public List<BigDecimal> calculateRSI(List<OHLCData> ohlcData, int period) {
        BarSeries series = buildBarSeries(ohlcData);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, period);

        List<BigDecimal> rsiValues = new ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            Num value = rsi.getValue(i);
            if (!value.isNaN()) {
                rsiValues.add(BigDecimal.valueOf(value.doubleValue()));
            }
        }
        return rsiValues;
    }

    public List<BigDecimal> calculateRSI(List<OHLCData> ohlcData) {
        return calculateRSI(ohlcData, defaultRsiPeriod);
    }

    public BigDecimal getLatestRSI(List<OHLCData> ohlcData, int period) {
        List<BigDecimal> values = calculateRSI(ohlcData, period);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    public BigDecimal getLatestRSI(List<OHLCData> ohlcData) {
        return getLatestRSI(ohlcData, defaultRsiPeriod);
    }

    public MACDResult calculateMACD(List<OHLCData> ohlcData, int shortPeriod, int longPeriod, int signalPeriod) {
        BarSeries series = buildBarSeries(ohlcData);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // MACD line = EMA(short) - EMA(long)
        org.ta4j.core.indicators.MACDIndicator macdLine = new org.ta4j.core.indicators.MACDIndicator(closePrice, shortPeriod, longPeriod);

        // Signal line = EMA of MACD line
        org.ta4j.core.indicators.EMAIndicator signalLine = new org.ta4j.core.indicators.EMAIndicator(macdLine, signalPeriod);

        List<BigDecimal> macdValues = new ArrayList<>();
        List<BigDecimal> signalValues = new ArrayList<>();
        List<BigDecimal> histogramValues = new ArrayList<>();

        for (int i = 0; i < series.getBarCount(); i++) {
            Num macdVal = macdLine.getValue(i);
            Num signalVal = signalLine.getValue(i);

            if (!macdVal.isNaN() && !signalVal.isNaN()) {
                macdValues.add(BigDecimal.valueOf(macdVal.doubleValue()));
                signalValues.add(BigDecimal.valueOf(signalVal.doubleValue()));
                histogramValues.add(BigDecimal.valueOf(macdVal.minus(signalVal).doubleValue()));
            }
        }

        return new MACDResult(macdValues, signalValues, histogramValues);
    }

    public MACDResult calculateMACD(List<OHLCData> ohlcData) {
        return calculateMACD(ohlcData, defaultMacdShortPeriod, defaultMacdLongPeriod, defaultMacdSignalPeriod);
    }

    public MACDResult getLatestMACD(List<OHLCData> ohlcData, int shortPeriod, int longPeriod, int signalPeriod) {
        MACDResult full = calculateMACD(ohlcData, shortPeriod, longPeriod, signalPeriod);
        if (full.macdLine().isEmpty()) {
            return new MACDResult(List.of(), List.of(), List.of());
        }
        int last = full.macdLine().size() - 1;
        return new MACDResult(
                List.of(full.macdLine().get(last)),
                List.of(full.signalLine().get(last)),
                List.of(full.histogram().get(last))
        );
    }

    public MACDResult getLatestMACD(List<OHLCData> ohlcData) {
        return getLatestMACD(ohlcData, defaultMacdShortPeriod, defaultMacdLongPeriod, defaultMacdSignalPeriod);
    }

    public BollingerBandsResult calculateBollingerBands(List<OHLCData> ohlcData, int period, double stdDevMultiplier) {
        BarSeries series = buildBarSeries(ohlcData);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        SMAIndicator sma = new SMAIndicator(closePrice, period);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, period);

        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd, DecimalNum.valueOf(stdDevMultiplier));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, DecimalNum.valueOf(stdDevMultiplier));

        List<BigDecimal> upperValues = new ArrayList<>();
        List<BigDecimal> middleValues = new ArrayList<>();
        List<BigDecimal> lowerValues = new ArrayList<>();

        for (int i = 0; i < series.getBarCount(); i++) {
            Num upper = bbUpper.getValue(i);
            Num middle = bbMiddle.getValue(i);
            Num lower = bbLower.getValue(i);

            if (!upper.isNaN() && !middle.isNaN() && !lower.isNaN()) {
                upperValues.add(BigDecimal.valueOf(upper.doubleValue()));
                middleValues.add(BigDecimal.valueOf(middle.doubleValue()));
                lowerValues.add(BigDecimal.valueOf(lower.doubleValue()));
            }
        }

        return new BollingerBandsResult(upperValues, middleValues, lowerValues);
    }

    public BollingerBandsResult calculateBollingerBands(List<OHLCData> ohlcData) {
        return calculateBollingerBands(ohlcData, defaultBbPeriod, defaultBbStdDev);
    }

    public BollingerBandsResult getLatestBollingerBands(List<OHLCData> ohlcData, int period, double stdDevMultiplier) {
        BollingerBandsResult full = calculateBollingerBands(ohlcData, period, stdDevMultiplier);
        if (full.upperBand().isEmpty()) {
            return new BollingerBandsResult(List.of(), List.of(), List.of());
        }
        int last = full.upperBand().size() - 1;
        return new BollingerBandsResult(
                List.of(full.upperBand().get(last)),
                List.of(full.middleBand().get(last)),
                List.of(full.lowerBand().get(last))
        );
    }

    public BollingerBandsResult getLatestBollingerBands(List<OHLCData> ohlcData) {
        return getLatestBollingerBands(ohlcData, defaultBbPeriod, defaultBbStdDev);
    }

    public SignalSummary getSignalSummary(String asset) throws IOException {
        CurrencyPair pair = new CurrencyPair(asset, "USD");
        List<OHLCData> ohlcData = ohlcService.getOHLCDataForPastYear(pair, KrakenOHLCService.Interval.fromString(ohlcInterval));

        if (ohlcData.size() < Math.max(defaultMacdLongPeriod + defaultMacdSignalPeriod, Math.max(defaultBbPeriod, defaultRsiPeriod)) + 1) {
            logger.warn("[INDICATOR] {} - Insufficient data for indicator calculation ({} candles)", asset, ohlcData.size());
            return null;
        }

        BigDecimal currentPrice = ohlcData.get(ohlcData.size() - 1).getClose();

        // RSI
        BigDecimal latestRSI = getLatestRSI(ohlcData);
        String rsiSignal = "neutral";
        if (latestRSI != null) {
            if (latestRSI.doubleValue() > 70) {
                rsiSignal = "overbought";
            } else if (latestRSI.doubleValue() < 30) {
                rsiSignal = "oversold";
            }
        }

        // MACD crossover
        MACDResult macdResult = calculateMACD(ohlcData);
        String macdCrossover = "neutral";
        if (macdResult.histogram().size() >= 2) {
            BigDecimal currentHistogram = macdResult.histogram().get(macdResult.histogram().size() - 1);
            BigDecimal previousHistogram = macdResult.histogram().get(macdResult.histogram().size() - 2);

            if (previousHistogram.doubleValue() <= 0 && currentHistogram.doubleValue() > 0) {
                macdCrossover = "bullish";
            } else if (previousHistogram.doubleValue() >= 0 && currentHistogram.doubleValue() < 0) {
                macdCrossover = "bearish";
            } else if (currentHistogram.doubleValue() > 0) {
                macdCrossover = "bullish_trend";
            } else {
                macdCrossover = "bearish_trend";
            }
        }

        // Bollinger Bands position
        BollingerBandsResult bbResult = calculateBollingerBands(ohlcData);
        String bbPosition = "between";
        if (!bbResult.upperBand().isEmpty()) {
            int last = bbResult.upperBand().size() - 1;
            BigDecimal upper = bbResult.upperBand().get(last);
            BigDecimal lower = bbResult.lowerBand().get(last);

            if (currentPrice.doubleValue() > upper.doubleValue()) {
                bbPosition = "above_upper";
            } else if (currentPrice.doubleValue() < lower.doubleValue()) {
                bbPosition = "below_lower";
            }
        }

        BigDecimal macdHistogram = null;
        if (!macdResult.histogram().isEmpty()) {
            macdHistogram = macdResult.histogram().get(macdResult.histogram().size() - 1);
        }

        BigDecimal bbUpper = bbResult.upperBand().isEmpty() ? null : bbResult.upperBand().get(bbResult.upperBand().size() - 1);
        BigDecimal bbMiddle = bbResult.middleBand().isEmpty() ? null : bbResult.middleBand().get(bbResult.middleBand().size() - 1);
        BigDecimal bbLower = bbResult.lowerBand().isEmpty() ? null : bbResult.lowerBand().get(bbResult.lowerBand().size() - 1);

        logger.info("[INDICATOR] {} - RSI: {} ({}), MACD crossover: {} (histogram: {}), BB: {} (price {} bands)",
                asset, latestRSI, rsiSignal, macdCrossover, macdHistogram, bbPosition, bbPosition);

        return new SignalSummary(asset, currentPrice, latestRSI, rsiSignal, macdHistogram, macdCrossover,
                bbUpper, bbMiddle, bbLower, bbPosition);
    }

    private BarSeries buildBarSeries(List<OHLCData> ohlcData) {
        BarSeries series = new BaseBarSeriesBuilder().withName("wastebot").build();

        Duration barDuration = inferDuration(ohlcData);

        for (OHLCData ohlc : ohlcData) {
            ZonedDateTime endTime = ZonedDateTime.ofInstant(Instant.ofEpochSecond(ohlc.getTimestamp()), ZoneId.systemDefault());
            BaseBar bar = new BaseBar(
                    barDuration,
                    endTime,
                    ohlc.getOpen(),
                    ohlc.getHigh(),
                    ohlc.getLow(),
                    ohlc.getClose(),
                    ohlc.getVolume()
            );
            series.addBar(bar);
        }

        return series;
    }

    private Duration inferDuration(List<OHLCData> ohlcData) {
        if (ohlcData.size() < 2) {
            return Duration.ofHours(1);
        }
        long diffSeconds = ohlcData.get(1).getTimestamp() - ohlcData.get(0).getTimestamp();
        return Duration.ofSeconds(diffSeconds);
    }

    public static record MACDResult(
            List<BigDecimal> macdLine,
            List<BigDecimal> signalLine,
            List<BigDecimal> histogram
    ) {}

    public static record SignalSummary(
            String asset,
            BigDecimal currentPrice,
            BigDecimal rsiValue,
            String rsiSignal,
            BigDecimal macdHistogram,
            String macdCrossover,
            BigDecimal bbUpper,
            BigDecimal bbMiddle,
            BigDecimal bbLower,
            String bbPosition
    ) {}
}
