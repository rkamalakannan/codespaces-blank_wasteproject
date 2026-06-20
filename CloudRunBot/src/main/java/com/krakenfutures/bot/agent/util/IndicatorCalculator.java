//package com.krakenfutures.wastebot.agent.util;
//
//import com.krakenfutures.wastebot.agent.model.IndicatorSnapshot;
//import org.ta4j.core.Bar;
//import org.ta4j.core.BarSeries;
//import org.ta4j.core.BaseBarSeries;
//import org.ta4j.core.BaseBarSeriesBuilder;
//import org.ta4j.core.indicators.averages.EMAIndicator;
//import org.ta4j.core.indicators.RSIIndicator;
//import org.ta4j.core.indicators.ATRIndicator;
//import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
//import org.ta4j.core.indicators.helpers.VolumeIndicator;
//import org.ta4j.core.indicators.averages.SMAIndicator;
//import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
//import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
//import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
//import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
//import org.ta4j.core.num.Num;
//
//import java.time.ZonedDateTime;
//import java.util.List;
//
//public class IndicatorCalculator {
//
//    public static BarSeries buildSeries(String name, List<Candle> candles) {
//        BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
//        for (Candle c : candles) {
//            ZonedDateTime time = ZonedDateTime.parse(c.time());
//            series.addBar(
//                    series.barBuilder().
//        }
//        return series;
//    }
//
//    public static IndicatorSnapshot calculateLatest(BarSeries series) {
//        if (series.getBarCount() == 0) {
//            return IndicatorSnapshot.empty();
//        }
//
//        int index = series.getEndIndex();
//        ClosePriceIndicator close = new ClosePriceIndicator(series);
//        VolumeIndicator volume = new VolumeIndicator(series);
//
//        EMAIndicator ema9 = new EMAIndicator(close, 9);
//        EMAIndicator ema21 = new EMAIndicator(close, 21);
//        RSIIndicator rsi = new RSIIndicator(close, 14);
//        ATRIndicator atr = new ATRIndicator(series, 14);
//
//        // Bollinger Bands (20, 2)
//        SMAIndicator sma20 = new SMAIndicator(close, 20);
//        StandardDeviationIndicator dev20 = new StandardDeviationIndicator(close, 20);
//        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
//        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, dev20);
//        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, dev20);
//
//        // Volume ratio: Volume / SMA(Volume, 20)
//        SMAIndicator smaVolume20 = new SMAIndicator(volume, 20);
//        double volumeRatio = 1.0;
//        if (series.getBarCount() >= 20) {
//            double currentVolume = volume.getValue(index).doubleValue();
//            double avgVolume = smaVolume20.getValue(index).doubleValue();
//            if (avgVolume > 0) {
//                volumeRatio = currentVolume / avgVolume;
//            }
//        }
//
//        // VWAP: sum(typicalPrice * volume) / sum(volume) for the last 20 periods
//        double sumPriceVolume = 0;
//        double sumVolume = 0;
//        int vwapLength = Math.min(20, series.getBarCount());
//        for (int i = 0; i < vwapLength; i++) {
//            int idx = index - i;
//            if (idx >= 0) {
//                Bar bar = series.getBar(idx);
//                double typicalPrice = (bar.getHighPrice().doubleValue() + bar.getLowPrice().doubleValue() + bar.getClosePrice().doubleValue()) / 3.0;
//                double vol = bar.getVolume().doubleValue();
//                sumPriceVolume += typicalPrice * vol;
//                sumVolume += vol;
//            }
//        }
//
//        double vwap = sumVolume > 0 ? sumPriceVolume / sumVolume : close.getValue(index).doubleValue();
//
//        return new IndicatorSnapshot(
//            ema9.getValue(index).doubleValue(),
//            ema21.getValue(index).doubleValue(),
//            rsi.getValue(index).doubleValue(),
//            vwap,
//            bbUpper.getValue(index).doubleValue(),
//            bbMiddle.getValue(index).doubleValue(),
//            bbLower.getValue(index).doubleValue(),
//            volumeRatio,
//            atr.getValue(index).doubleValue()
//        );
//    }
//
//    public record Candle(String time, double open, double high, double low, double close, double volume) {}
//}
