package com.hope.xchangepractice;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import ta4jexamples.bots.KrakenOHLCLoader;

public class TickerLoader {

    public static BarSeries createBarSeries() {

        BarSeries series = new BaseBarSeriesBuilder()
                .withMaxBarCount(Integer.MAX_VALUE).build();
        try {
            KrakenOHLCs krakenOHLCs = new KrakenOHLCLoader().getKrakenOHLCs();
            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
                BaseBar bar = new BaseBar(
                        Duration.ofSeconds(1),
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

    public static KrakenMarketDataService marketData() {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        return (KrakenMarketDataService) krakenExchange.getMarketDataService();
    }
}
