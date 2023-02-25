package com.hope.xchangepractice;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.eclipse.jetty.server.Server;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DoubleNum;

import info.bitrich.xchangestream.kraken.KrakenStreamingExchange;

public class TickerLoader {

    public static BarSeries createBarSeries() {

        BarSeries series = new BaseBarSeriesBuilder()
                .withMaxBarCount(Integer.MAX_VALUE).build();
        try {
            KrakenMarketDataService marketDataService = marketData();
            LocalDateTime time = LocalDateTime.now().minusWeeks(1);
            ZoneId zoneId = ZoneId.systemDefault();
            long epoch = time.atZone(zoneId).toEpochSecond();
            KrakenOHLCs krakenOHLCs = marketDataService.getKrakenOHLC(new CurrencyPair("BTC", "USD"), 15, epoch);
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

    public static BarSeries liveMarketData() throws IOException {
        BarSeries liveSeries = new BaseBarSeriesBuilder().build();
        KrakenMarketDataService marketDataService = marketData();
        Ticker ticker = marketDataService.getTicker(CurrencyPair.BTC_USD);

        BaseBar baseBar =  new BaseBar(Duration.ofSeconds(1), ZonedDateTime.now(), ticker.getOpen(), ticker.getHigh(),
                ticker.getLow(), ticker.getLast(),
                ticker.getVolume());
        
        liveSeries.addBar(baseBar);
        
        return liveSeries;


    }

    private static KrakenMarketDataService marketData() {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        return (KrakenMarketDataService) krakenExchange.getMarketDataService();
    }
}
