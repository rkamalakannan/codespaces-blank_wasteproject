/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package ta4jexamples.bots;

import com.api.SubmitClient;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.binance.BinanceExchange;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

/**
 * @author vscode
 */
public class ArbitrageTrading {

    public static Ticker binanceExchangeSettings() throws IOException {
        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(BinanceExchange.class);
        BinanceMarketDataService marketDataService = (BinanceMarketDataService) exchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("MATIC", "USDT");
        return marketDataService.getTicker(instrument);
    }

    public static Ticker krakenExchangeSettings() throws IOException {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("MATIC", "USD");

        return marketDataService.getTicker(instrument);
    }

    // binance counter high -

    // if kraken is lower than binance -> buy kraken limit order to binance value
    //  sell with binance value.
    // binace counter low:

    // if kraken is higher than binance: sell kraken limit order to binance value
    public static void main(String[] args)
            throws IOException, KeyManagementException, InvalidKeyException, NoSuchAlgorithmException, InterruptedException {

        while (true) {
            Thread.sleep(100000);
            Ticker binanceTicker = binanceExchangeSettings();
            String symbol = "pf_maticusd";
            BigDecimal lastPriceKrakenFuture = SubmitClient.findTicker(symbol);
            BigDecimal lastPriceBinance = binanceTicker.getLast();
            System.out.println("BINANCE: " + lastPriceBinance);
            System.out.println("KRAKEN: " + lastPriceKrakenFuture);

            if (lastPriceBinance.compareTo(lastPriceKrakenFuture) > 0) {
                SubmitClient.buyLimitOrder(symbol, lastPriceKrakenFuture);
                SubmitClient.sellLimitOrder(symbol, lastPriceBinance);

            } else if (lastPriceBinance.compareTo(lastPriceKrakenFuture) < 0) {
                SubmitClient.sellLimitOrder(symbol, lastPriceKrakenFuture);
                SubmitClient.buyLimitOrder(symbol, lastPriceBinance);

            } else {
                // do nothing
            }

        }
    }

}
