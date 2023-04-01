package ta4jexamples.bots;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import org.knowm.xchange.dto.trade.*;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.krakenfutures.dto.trade.KrakenFuturesOrderFlags;
import org.knowm.xchange.dto.account.OpenPosition;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.knowm.xchange.krakenfutures.KrakenFuturesExchange;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesMarketDataService;
import org.knowm.xchange.service.trade.params.*;
import org.knowm.xchange.service.trade.params.DefaultTradeHistoryParamInstrument;
import org.knowm.xchange.service.trade.params.TradeHistoryParamInstrument;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class KrakenFutureTrading {
    
    public static Ticker krakenFuturesExchangeSettings() throws IOException, InterruptedException {

        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
        spec.setApiKey("8TfYWFiQi7rfNeWcGw43VBMn+6vUEX5aXJFpC+9d2t9HhPfi+wvWtm+n");
        spec.setSecretKey("485DCqYfbb2hK4gsbB7NwPHz4esjfT1K9vvFdrP0Wwaq4+Qk8wirhxJHvVYKGTlenSrNawhleF+u2MLm57/k4ghO");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);

        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(spec);
        Instrument instrument = new CurrencyPair("BTC", "USD");

        // checkAccount(exchange);
        // placeOrderCheckOpenOrdersAndCancel(exchange, instrument);
        // placeStopOrderAndGetOpenOrders(exchange, instrument);
        // cancelAllOrdersByInstrument(exchange, instrument);
        // placeMarketOrderAndGetTradeHistory(exchange, instrument);
        // checkTradeHistory(exchange, instrument);

        checkOpenPositions(exchange, instrument);

        KrakenFuturesMarketDataService marketDataService = (KrakenFuturesMarketDataService) exchange.getMarketDataService();
        Ticker ticker =  marketDataService.getTicker(instrument);
    }

    public static Ticker krakenExchangeSettings() throws IOException {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("BTC", "USD");
        return marketDataService.getTicker(instrument);
    }


    public static void checkAccount(Exchange exchange) throws IOException {
        AccountInfo accountInfo = exchange.getAccountService().getAccountInfo();
        System.out.println("Account Info:"+accountInfo);
    }


    public static void placeOrderCheckOpenOrdersAndCancel(Exchange exchange, Instrument instrument) throws IOException {
        String orderId = exchange.getTradeService().placeLimitOrder(new LimitOrder.Builder(Order.OrderType., instrument)
                        .limitPrice(BigDecimal.valueOf(1000))
                        .originalAmount(BigDecimal.valueOf(0.001))
                .build());
        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        System.out.println(openOrders.get(0).toString());
        // assertThat(openOrders.get(0).getInstrument()).isEqualTo(instrument);
        // assertThat(openOrders.get(0).getId()).isEqualTo(orderId);
        // assertThat(exchange.getTradeService().cancelOrder(orderId)).isTrue();
    }

    public static void checkOpenPositions(Exchange exchange, Instrument instrument) throws IOException {
        List<OpenPosition> openPositions = exchange.getTradeService().getOpenPositions().getOpenPositions();
        System.out.println("Open Positions"+openPositions);
    }

    public static void placeStopOrderAndGetOpenOrders(Exchange exchange, Instrument instrument, Ticker ticker) throws IOException {
        String orderId = exchange.getTradeService().placeStopOrder(new StopOrder.Builder(Order.OrderType.ASK,instrument)
                .intention(StopOrder.Intention.STOP_LOSS)
                .stopPrice(BigDecimal.valueOf(190000))
                .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                .originalAmount(BigDecimal.valueOf(0.001))
                .build());
        System.out.println("OrderId"+orderId);
        OpenOrders openOrders = exchange.getTradeService().getOpenOrders();
        System.out.println("HiddenOrders:"+openOrders.getHiddenOrders());
    }

    public static void placeMarketOrderAndGetTradeHistory(Exchange exchange, Instrument instrument) throws IOException, InterruptedException {
        // String orderId = exchange.getTradeService().placeMarketOrder(new MarketOrder.Builder(Order.OrderType.BID,instrument)
        //         .originalAmount(BigDecimal.ONE)
        //         .build());
        TimeUnit.SECONDS.sleep(1);
        TradeHistoryParamInstrument params = (TradeHistoryParamInstrument) exchange.getTradeService().createTradeHistoryParams();
        params.setInstrument(instrument);
        List<UserTrade> userTrades = exchange.getTradeService().getTradeHistory(params).getUserTrades();
        Collections.reverse(userTrades);
        System.out.println("userTrades:"+userTrades);
    }

    public static void cancelAllOrdersByInstrument(Exchange exchange, Instrument instrument) throws IOException {
        // exchange.getTradeService().placeLimitOrder(new LimitOrder.Builder(Order.OrderType.BID,instrument)
        //         .limitPrice(BigDecimal.valueOf(1000))
        //         .originalAmount(BigDecimal.ONE)
        //         .build());
        // exchange.getTradeService().placeLimitOrder(new LimitOrder.Builder(Order.OrderType.BID,instrument)
        //         .limitPrice(BigDecimal.valueOf(1000))
        //         .originalAmount(BigDecimal.ONE)
        //         .build());
        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        System.out.println(openOrders.get(0).toString());
        // Collection<String> orderIds = exchange.getTradeService().cancelAllOrders(new DefaultCancelAllOrdersByInstrument(instrument));
        // orderIds.forEach(System.out::println);
   
    }

    public static void checkTradeHistory(Exchange exchange,Instrument instrument) throws IOException {
        List<UserTrade> userTrades = exchange.getTradeService().getTradeHistory(new DefaultTradeHistoryParamInstrument(instrument)).getUserTrades();
        System.out.println("usertrades"+userTrades);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
       Ticker ticker =  krakenFuturesExchangeSettings();
       System.out.println(ticker.toString());
    }
}
