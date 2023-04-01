package ta4jexamples.bots;

import java.io.IOException;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.knowm.xchange.krakenfutures.KrakenFuturesExchange;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesMarketDataService;

public class KrakenFutureTrading {
    
    public static Ticker krakenFuturesExchangeSettings() throws IOException {

        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
        spec.setApiKey("8TfYWFiQi7rfNeWcGw43VBMn+6vUEX5aXJFpC+9d2t9HhPfi+wvWtm+n");
        spec.setSecretKey("485DCqYfbb2hK4gsbB7NwPHz4esjfT1K9vvFdrP0Wwaq4+Qk8wirhxJHvVYKGTlenSrNawhleF+u2MLm57/k4ghO");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);

        Exchange exchange = ExchangeFactory.INSTANCE.createExchange(spec);

        checkAccount(exchange);

        KrakenFuturesMarketDataService marketDataService = (KrakenFuturesMarketDataService) exchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("BTC", "USD");
        return marketDataService.getTicker(instrument);
    }

    public static Ticker krakenExchangeSettings() throws IOException {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
        Instrument instrument = new CurrencyPair("BTC", "USD");
        return marketDataService.getTicker(instrument);
    }


    public static void checkAccount(Exchange exchange) throws IOException {
        AccountInfo accountInfo = exchange.getAccountService().getAccountInfo();
        System.out.println(accountInfo);
    }


    public void placeOrderCheckOpenOrdersAndCancel() throws IOException {
        String orderId = exchange.getTradeService().placeLimitOrder(new LimitOrd.Builder(Order.OrderType.BID,instrument)
                        .limitPrice(BigDecimal.valueOf(1000))
                        .originalAmount(BigDecimal.ONE)
                .build());
        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        System.out.println(openOrders.get(0).toString());
        assertThat(openOrders.get(0).getInstrument()).isEqualTo(instrument);
        assertThat(openOrders.get(0).getId()).isEqualTo(orderId);
        assertThat(exchange.getTradeService().cancelOrder(orderId)).isTrue();
    }

    public static void main(String[] args) throws IOException {
       Ticker ticker =  krakenFuturesExchangeSettings();
       System.out.println(ticker.toString());
    }
}
