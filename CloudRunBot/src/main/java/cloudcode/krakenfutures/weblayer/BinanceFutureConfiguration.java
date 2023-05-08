package cloudcode.krakenfutures.weblayer;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.binance.BinanceUsExchange;
import org.knowm.xchange.binance.dto.marketdata.BinanceFundingRate;
import org.knowm.xchange.binance.service.BinanceMarketDataServiceRaw;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class BinanceFutureConfiguration {

    private Exchange binanceFutureExchange = createExchange();

    public Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(BinanceUsExchange.class);
        spec.setHost(BinanceUsExchange.FUTURES_URL);
        return ExchangeFactory.INSTANCE.createExchange(spec); //test
    }

    public BinanceFundingRate getBinanceFutureTicker() throws IOException {
        BinanceMarketDataServiceRaw marketDataService = (BinanceMarketDataServiceRaw) binanceFutureExchange
                .getMarketDataService();

        Instrument instrument = new CurrencyPair("BTC", "USDT");

        return marketDataService.getBinanceFundingRate(instrument);
    }

}
