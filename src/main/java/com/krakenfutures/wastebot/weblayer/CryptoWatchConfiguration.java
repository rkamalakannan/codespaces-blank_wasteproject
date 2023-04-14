// package com.krakenfutures.wastebot.weblayer;

// import java.io.IOException;

// import org.knowm.xchange.Exchange;
// import org.knowm.xchange.ExchangeFactory;
// import org.knowm.xchange.cryptowatch.Cryptowatch;
// import org.knowm.xchange.cryptowatch.CryptowatchExchange;
// import org.knowm.xchange.cryptowatch.dto.marketdata.CryptowatchPriceChange;
// import org.knowm.xchange.cryptowatch.service.CryptowatchMarketDataServiceRaw;
// import org.knowm.xchange.currency.CurrencyPair;
// import org.knowm.xchange.instrument.Instrument;

// public class CryptoWatchConfiguration {
    
//     private Exchange cryptoExchange = getCryptoWatchExchangeConfiguration();

//     public Exchange getCryptoWatchExchangeConfiguration() {
//         return ExchangeFactory.INSTANCE.createExchange(CryptowatchExchange.class);
//     }

//     public CryptowatchPriceChange getPriceChange(Instrument instrument) throws IOException {
//         CryptowatchMarketDataServiceRaw marketDataService = (CryptowatchMarketDataServiceRaw) cryptoExchange.getMarketDataService();
//         System.out.println(getCryptowatchAssets().stream().toList().toString()
//         marketDataService.getCryptowatchPrice(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USD"), "krakenfuture").

//         return marketDataService.getCrypto(new CurrencyPair(instrument.getBase().getCurrencyCode(), "USD"));
//     }

// }
