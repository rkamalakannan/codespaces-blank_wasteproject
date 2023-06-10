/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package cloudcode.krakenfutures.weblayer;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.knowm.xchange.kraken.service.KrakenMarketDataServiceRaw;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * @author vscode
 */
@Component
public class KrakenSpotConfiguration {

    private Exchange krakenSpotExchange = getKrakenSpotExchangeSettings();

    public Exchange getKrakenSpotExchangeSettings() {
        return ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
    }

    public KrakenTicker getKrakenSpotTicker(Instrument instrument) throws IOException {
        KrakenMarketDataServiceRaw marketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();

        java.util.Map<String, KrakenTicker> instruments = marketDataService.getKrakenTickers();

        return marketDataService.getKrakenTicker(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USD"));
    }

    public KrakenOHLCs getKrakenOHLCs(Instrument instrument) throws IOException {
        KrakenExchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService krakenMarketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        LocalDateTime time = LocalDateTime.now().minusDays(1);
        ZoneId zoneId = ZoneId.systemDefault();
        long epoch = time.atZone(zoneId).toEpochSecond();
        return krakenMarketDataService.getKrakenOHLC(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USD"), 1, epoch);
    }

    public KrakenOHLCs getAssetsOHLCs(Instrument instrument) throws IOException {
        KrakenExchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService krakenMarketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        LocalDateTime time = LocalDateTime.now().minusMinutes(1);
        ZoneId zoneId = ZoneId.systemDefault();
        long epoch = time.atZone(zoneId).toEpochSecond();
        return krakenMarketDataService.getKrakenOHLC(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USD"), 1440, epoch);
    }

    public Map<String, KrakenTicker> getKrakenAssets() throws IOException {
        KrakenMarketDataServiceRaw marketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();

        Map<String, KrakenTicker> instruments = marketDataService.getKrakenTickers();

        return instruments;
    }
}