package com.hope.xchangepractice;

import java.util.List;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.huobi.HuobiExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.bitrich.xchangestream.binance.BinanceStreamingExchange;
import info.bitrich.xchangestream.bitstamp.v2.BitstampStreamingExchange;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingExchangeFactory;
import io.reactivex.disposables.Disposable;

public class XchangepracticeApplication {

  private static final Logger LOG = LoggerFactory.getLogger(XchangepracticeApplication.class);

  public static void main(String[] args) throws InterruptedException {

    //Infinity//
    while (true) {
      // Use StreamingExchangeFactory instead of ExchangeFactory
      StreamingExchange exchange = StreamingExchangeFactory.INSTANCE.createExchange(BitstampStreamingExchange.class);

      // Connect to the Exchange WebSocket API. Here we use a blocking wait.
      exchange.connect().blockingAwait();

      // Subscribe to live trades update.
      Disposable subscription1 = exchange.getStreamingMarketDataService().getTicker(CurrencyPair.BTC_USDT, exchange)
          .subscribe(
              ticker -> LOG.info("Ticker: {}", ticker),
              throwable -> LOG.error("Error in trade subscription", throwable));

      // Subscribe order book data with the reference to the subscription.
      // Disposable subscription2 = exchange.getStreamingMarketDataService()
      // .getOrderBook(CurrencyPair.BTC_USD)
      // .subscribe(orderBook -> LOG.info("Order book: {}", orderBook));

      // Wait for a while to see some results arrive
      Thread.sleep(20000);

      // Unsubscribe
      subscription1.dispose();
      // subscription2.dispose();

      // Disconnect from exchange (blocking again)
      exchange.disconnect().blockingAwait();
    }
  }
}