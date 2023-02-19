package com.hope.xchangepractice;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DoubleNum;

public class TickerLoader {

    public static BarSeries createBarSeries() {
    
        BarSeries series = new BaseBarSeriesBuilder().withNumTypeOf(DoubleNum::valueOf).withMaxBarCount(Integer.MAX_VALUE).build();
        try {
            Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
            KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
            LocalDateTime time = LocalDateTime.now().minusWeeks(1);
            ZoneId zoneId = ZoneId.systemDefault();
            long epoch = time.atZone(zoneId).toEpochSecond();
            KrakenOHLCs krakenOHLCs = marketDataService.getKrakenOHLC(new CurrencyPair("BCH", "USD"), 15, epoch);
            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
                BaseBar bar = new BaseBar(
                        Duration.ofMinutes(15),
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()),
                                ZoneId.systemDefault()),
                        krakenOHLC.getOpen().doubleValue(),
                        krakenOHLC.getHigh().doubleValue(),
                        krakenOHLC.getLow().doubleValue(),
                        krakenOHLC.getClose().doubleValue(),
                        krakenOHLC.getVolume().doubleValue());
                series.addBar(bar);
    
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return series;
    }
    
}
