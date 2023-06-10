/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package cloudcode.krakenfutures.weblayer;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenAssetPairs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.kraken.dto.trade.KrakenOrderFlags;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.knowm.xchange.kraken.service.KrakenMarketDataServiceRaw;
import org.springframework.stereotype.Component;
import org.ta4j.core.num.Num;

import java.io.IOException;
import java.math.BigDecimal;
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
        ExchangeSpecification spec = new ExchangeSpecification(KrakenExchange.class);
        spec.setSecretKey("qJ+TcfyXWA36m3k5SAX2KKdyWYV5r6ID1tqrpUX3Tn06O4v7T2gQ62F16oHpkNHcV0AVSx6I1ucTjwxMpH46lQ==");
        spec.setApiKey("RbnDnXwmFmdZhhfia3+Z7LFceefn1ZWeoYzpB8n2nGX7bDX8iuKMg1UI");
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    public KrakenTicker getKrakenSpotTicker(Instrument instrument) throws IOException {
        KrakenMarketDataServiceRaw marketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();

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
        LocalDateTime time = LocalDateTime.now().minusYears(1);
        ZoneId zoneId = ZoneId.systemDefault();
        long epoch = time.atZone(zoneId).toEpochSecond();
        return krakenMarketDataService.getKrakenOHLC(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USD"), 1440, epoch);
    }

    public Map<String, KrakenTicker> getKrakenAssets() throws IOException {
        KrakenMarketDataServiceRaw marketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();
        return marketDataService.getKrakenTickers();
    }

    public String submitLimitOrder(Instrument instrument, BigDecimal originalAmount, Num limitPrice) throws IOException {
        return krakenSpotExchange.getTradeService()
                .placeLimitOrder(new LimitOrder.Builder(Order.OrderType.BID, instrument)
                        .limitPrice(BigDecimal.valueOf(Long.parseLong(limitPrice.toString())))
                        .flag(KrakenOrderFlags.POST)
                        .originalAmount(originalAmount)
                        .build());
    }

    public KrakenAssetPairs getKrakenAssetPairs(CurrencyPair currencyPair) throws IOException {
        KrakenMarketDataServiceRaw marketDataService = (KrakenMarketDataServiceRaw) krakenSpotExchange
                .getMarketDataService();
        return marketDataService.getKrakenAssetPairs(currencyPair);
    }

}