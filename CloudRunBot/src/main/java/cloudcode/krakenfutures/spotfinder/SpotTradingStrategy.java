package cloudcode.krakenfutures.spotfinder;

import cloudcode.krakenfutures.weblayer.KrakenSpotConfiguration;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.*;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ROCIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class SpotTradingStrategy {

    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;

    public void executor(BigDecimal originalAmount) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Map.Entry<String, KrakenTicker>> map = findInstrumentsAll(originalAmount);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public List<Map.Entry<String, KrakenTicker>> findInstrumentsAll(BigDecimal originalAmount) throws IOException {

        List<Map.Entry<String, KrakenTicker>> test = krakenSpotConfiguration.getKrakenAssets().entrySet().stream().filter(tickerEntry -> tickerEntry.getValue().getClose().getPrice().compareTo(BigDecimal.ONE) < 0)
                .filter(tickerEntry -> tickerEntry.getKey().endsWith("USD"))
                .collect(Collectors.toList());
        List<String> result = test.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        result.forEach(s -> {
            try {
                String asset = s.replace("USD", "").trim();
                System.out.println(asset);
                Instrument instrument = new CurrencyPair(asset, "USD");
                placeOrder(instrument, originalAmount);
            } catch (IOException e) {
                throw new RuntimeException();
            }
        });
        return test;
    }

    public void placeOrder(Instrument instrument, BigDecimal originalAmount) throws IOException {
        BarSeries series = createBarSeries(instrument);
        boolean isSatisfied = isBuySatisfied(series, instrument, originalAmount);
        if (isSatisfied) {
            System.out.println("Condition Satisfied for Instrument:" + instrument.getBase());
        }
    }

    public BarSeries createBarSeries(Instrument instrument) {

        BarSeries series = new BaseBarSeriesBuilder().withMaxBarCount(Integer.MAX_VALUE).build();

        try {
            KrakenOHLCs krakenOHLCs = getOhlc(instrument);
            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
                BaseBar bar = new BaseBar(Duration.ofMinutes(1440), ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()), ZoneId.systemDefault()), krakenOHLC.getOpen(), krakenOHLC.getHigh(), krakenOHLC.getLow(), krakenOHLC.getClose(), krakenOHLC.getVolume());
                series.addBar(bar);

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return series;
    }

    public KrakenOHLCs getOhlc(Instrument instrument) throws IOException {

        return krakenSpotConfiguration.getAssetsOHLCs(instrument);

    }

    public boolean isBuySatisfied(BarSeries series, Instrument instrument, BigDecimal originalAmount) throws IOException {
        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        MACDIndicator macd = new MACDIndicator(closePrice);

        EMAIndicator emaMacd = new EMAIndicator(macd, 9);


        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, 14);


        SuperTrendIndicator superTrendIndicator = new SuperTrendIndicator(series);

        Indicator<Num> superTrendLowIndicator = superTrendIndicator.getSuperTrendLowerBandIndicator();
        Indicator<Num> superTrendUpIndicator = superTrendIndicator.getSuperTrendUpperBandIndicator();

        System.out.println("Up" + superTrendUpIndicator.getValue(series.getEndIndex()));
        System.out.println("Low" + superTrendLowIndicator.getValue(series.getEndIndex()));

        System.out.println("SuperTrendIndicator" + superTrendIndicator.getValue(series.getEndIndex()));

        System.out.println("close" + closePrice.getValue(series.getEndIndex()));

        ROCIndicator rocIndicator = new ROCIndicator(closePrice, 9);


        Rule entryRule = new UnderIndicatorRule(rsiIndicator, 30
        ).or(new UnderIndicatorRule(rocIndicator, 0));

        Rule macdEntryRule = new CrossedUpIndicatorRule(macd, emaMacd);
        Rule macdExitRule = new CrossedDownIndicatorRule(macd, emaMacd);

        System.out.println("Entry Rule Satisfied:" + entryRule.isSatisfied(series.getEndIndex()));

        return entryRule.isSatisfied(series.getEndIndex());

    }

}
