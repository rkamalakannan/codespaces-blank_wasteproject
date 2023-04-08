package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.binance.Binance;
import org.knowm.xchange.binance.BinanceExchange;
import org.knowm.xchange.binance.BinanceFutures;
import org.knowm.xchange.binance.BinanceFuturesAuthenticated;
import org.knowm.xchange.binance.BinanceUsExchange;
import org.knowm.xchange.binance.dto.marketdata.BinanceFundingRate;
import org.knowm.xchange.binance.dto.marketdata.BinancePrice;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.BinanceExchangeInfo;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.Symbol;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.binance.service.BinanceMarketDataServiceRaw;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenAssetPairs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.kraken.service.KrakenMarketDataServiceRaw;
import org.springframework.stereotype.Component;

@Component
public class BinanceFutureConfiguration {

    public Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(BinanceUsExchange.class);
        spec.setHost(BinanceExchange.FUTURES_URL);
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    private Exchange binanceFutureExchange = createExchange();

    public Exchange getBinanceFutureExchangeSettings() {
        return ExchangeFactory.INSTANCE.createExchange(BinanceExchange.class);
    }

    public BinanceFundingRate getBinanceFutureTicker() throws IOException {
        BinanceMarketDataServiceRaw marketDataService = (BinanceMarketDataServiceRaw) binanceFutureExchange
                .getMarketDataService();

        Instrument instrument = new CurrencyPair("BTC", "USDT");
        
        return marketDataService.getBinanceFundingRate(instrument);
    }

}
