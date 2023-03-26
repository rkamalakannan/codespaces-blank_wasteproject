package ta4jexamples.bots;

import java.io.IOException;
import java.math.BigDecimal;

import org.apache.commons.beanutils.ResultSetIterator;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.binance.BinanceUsExchange;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.api.SubmitClient;

@RestController
class BotRun {

    public static Ticker binanceExchangeSettings() throws IOException {
        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(BinanceUsExchange.class);
        BinanceMarketDataService marketDataService = (BinanceMarketDataService) exchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("BTC", "USDT");
        return marketDataService.getTicker(instrument);
    }

    public static Ticker krakenExchangeSettings() throws IOException {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("BTC", "USD");

        return marketDataService.getTicker(instrument);
    }

    // binance counter high -
    // if kraken is lower than binance -> buy kraken limit order to binance value
    //  sell with binance value.
    // binace counter low:
    // if kraken is higher than binance: sell kraken limit order to binance value
    @GetMapping("/v1/execute")
    public Boolean executeBot() {

        boolean reduceOnly = false;
        boolean result = false;
        try {
            Exchange exchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
            MarketDataService marketDataService = exchange.getMarketDataService();
            Ticker ticker = marketDataService.getTicker(new CurrencyPair("BTC", "USD"));
            String symbol = "pf_xbtusd";
            BigDecimal lastPriceKrakenFuture = SubmitClient.findTicker(symbol);
            BigDecimal lastPriceBinance = ticker.getLast();
            System.out.println("BINANCE: " + lastPriceBinance);
            System.out.println("KRAKEN: " + lastPriceKrakenFuture);
            if (lastPriceBinance.compareTo(lastPriceKrakenFuture) > 0) {
                SubmitClient.buyLimitOrder(symbol, lastPriceKrakenFuture, reduceOnly);
                SubmitClient.sellLimitOrder(symbol, lastPriceBinance, reduceOnly);
                result = true;

            } else if (lastPriceBinance.compareTo(lastPriceKrakenFuture) < 0) {
                SubmitClient.sellLimitOrder(symbol, lastPriceKrakenFuture, reduceOnly);
                SubmitClient.buyLimitOrder(symbol, lastPriceBinance, reduceOnly);
                result = true;

            } else {
                // do nothing
            }
        } catch (Exception e) {
        
        }
        return result;
    
    }

}
