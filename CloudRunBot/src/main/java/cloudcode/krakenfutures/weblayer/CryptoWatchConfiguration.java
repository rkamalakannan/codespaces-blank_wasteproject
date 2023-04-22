package cloudcode.krakenfutures.weblayer;

import java.io.IOException;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.cryptowatch.CryptowatchExchange;
import org.knowm.xchange.cryptowatch.dto.marketdata.CryptowatchSummary;
import org.knowm.xchange.cryptowatch.service.CryptowatchMarketDataServiceRaw;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.instrument.Instrument;
import org.springframework.stereotype.Component;

@Component
public class CryptoWatchConfiguration {
    
    private Exchange cryptoExchange = getCryptoWatchExchangeConfiguration();

    public Exchange getCryptoWatchExchangeConfiguration() {
        return ExchangeFactory.INSTANCE.createExchange(CryptowatchExchange.class);
    }

    public CryptowatchSummary getFuturesPriceChange(Instrument instrument) throws IOException {
        CryptowatchMarketDataServiceRaw marketDataService = (CryptowatchMarketDataServiceRaw) cryptoExchange.getMarketDataService();
        return marketDataService.getCryptowatchSummary(new CurrencyPair("", instrument.getBase().getCurrencyCode()+"usd-perpetual-future-multi"), "kraken-futures");
    }

    public CryptowatchSummary getSpotPriceChange(Instrument instrument) throws IOException {
        CryptowatchMarketDataServiceRaw marketDataService = (CryptowatchMarketDataServiceRaw) cryptoExchange.getMarketDataService();
        return marketDataService.getCryptowatchSummary(new CurrencyPair(instrument.getBase(), instrument.getCounter()), "kraken");
    }


    

}
