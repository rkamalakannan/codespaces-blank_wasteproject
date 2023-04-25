package cloudcode.krakenfutures.strategy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.Strategy;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import cloudcode.krakenfutures.weblayer.KrakenSpotConfiguration;

@Component
public class AveragePricingStragegy {


    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;
    
    public KrakenOHLCs getOhlc5m(Instrument instrument) throws IOException {
        
        return krakenSpotConfiguration.getKrakenOHLCs(instrument);
        
    }

    public BarSeries createBarSeries(Instrument instrument) {

        BarSeries series = new BaseBarSeriesBuilder()
                .withMaxBarCount(Integer.MAX_VALUE).build();
        try {
            KrakenOHLCs krakenOHLCs = getOhlc5m(instrument);
            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
                BaseBar bar = new BaseBar(
                        Duration.ofMinutes(15),
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()),
                                ZoneId.systemDefault()),
                        krakenOHLC.getOpen(),
                        krakenOHLC.getHigh(),
                        krakenOHLC.getLow(),
                        krakenOHLC.getClose(),
                        krakenOHLC.getVolume());
                series.addBar(bar);

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return series;
    }

    public double findAveragePrice(Instrument instrument, BigDecimal originalAmount) {
        BarSeries series = createBarSeries(instrument);
        BarSeries lastBarSeries = new BaseBarSeriesBuilder().withName("lastBarSeries").build();
        lastBarSeries.addBar(series.getLastBar());
        System.out.println("LastBarSeriers"+lastBarSeries.getBarData().toString());
        TypicalPriceIndicator typicalPriceIndicator = new TypicalPriceIndicator(lastBarSeries);
        RSIIndicator rsiIndicator = new RSIIndicator(typicalPriceIndicator, 30);
        System.out.println(rsiIndicator.getValue(0).doubleValue());
        return typicalPriceIndicator.getValue(0).doubleValue();
    }


}
