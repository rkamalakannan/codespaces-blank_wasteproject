/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.krakenfutures.KrakenFuturesExchange;
import org.knowm.xchange.krakenfutures.dto.trade.KrakenFuturesOrderFlags;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesMarketDataService;
import org.knowm.xchange.service.trade.params.DefaultCancelAllOrdersByInstrument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 *
 * @author vscode
 */
@Component
public class KrakenFutureConfiguration {

    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;

    private Exchange exchange = createExchange();

    public Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
        spec.setApiKey("8TfYWFiQi7rfNeWcGw43VBMn+6vUEX5aXJFpC+9d2t9HhPfi+wvWtm+n");
        spec.setSecretKey("485DCqYfbb2hK4gsbB7NwPHz4esjfT1K9vvFdrP0Wwaq4+Qk8wirhxJHvVYKGTlenSrNawhleF+u2MLm57/k4ghO");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    public Ticker getTickers(Instrument instrument) throws IOException {
        KrakenFuturesMarketDataService marketDataService = (KrakenFuturesMarketDataService) exchange
                .getMarketDataService();
        return marketDataService.getTicker(instrument);
    }

    public void placeOrder(Instrument instrument, BigDecimal originalAmount) throws IOException {
        Ticker krakenFutureTicker = getTickers(instrument);
        Ticker krakenSpotTicker = krakenSpotConfiguration.getKrakenSpotTicker(instrument);

        BigDecimal krakenFutureLastValue = krakenFutureTicker.getLast().abs();
        BigDecimal krakenSpotLastValue = krakenSpotTicker.getLast().abs();

        // if kraken spot lower than future value
        // sell future value and buy kraken spot value
        // ask sell
        // bid buy
        if (krakenSpotLastValue.compareTo(krakenFutureLastValue) > 0) {
            placeLimitOrder(instrument, originalAmount, "BID", krakenFutureTicker);
            placeStopOrder(instrument, originalAmount, "ASK", krakenFutureTicker);
            placeTakeProfitOrder(instrument, originalAmount, "ASK", krakenSpotTicker);

        }
        // if kraken spot higher than future value
        // buy future value and sell kraken spot value
        else if (krakenSpotLastValue.compareTo(krakenFutureLastValue) < 0) {
            placeLimitOrder(instrument, originalAmount, "ASK", krakenFutureTicker);
            placeStopOrder(instrument, originalAmount, "BID", krakenSpotTicker);
            placeTakeProfitOrder(instrument, originalAmount, "BID", krakenSpotTicker);

        } else {
            // do nothing
        }

    }

    public void placeLimitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, Ticker ticker)
            throws IOException {

        BigDecimal limitPrice = ticker.getLast().setScale(0, RoundingMode.DOWN);

        String orderId = exchange.getTradeService()
                .placeLimitOrder(new LimitOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                        .limitPrice(limitPrice)
                        .originalAmount(originalAmount)
                        .build());

        System.out.println("Placed Limit Order " + bidType + "for value" + limitPrice + "with order id :" + orderId);
    }

    public void placeStopOrder(Instrument instrument, BigDecimal originalAmount, String bidType, Ticker ticker)
            throws IOException {
        BigDecimal stopPrice;
        if (bidType.equals("BID")) {
            stopPrice = ticker.getLast().plus().add(ticker.getLast().multiply(BigDecimal.valueOf(1 / 100.0)));
        } else {
            stopPrice = ticker.getLast().subtract(ticker.getLast().multiply(BigDecimal.valueOf(1 / 100.0)));
        }
        stopPrice = stopPrice.setScale(0, RoundingMode.DOWN);

        String orderId = exchange.getTradeService()
                .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                        .intention(StopOrder.Intention.STOP_LOSS)
                        .stopPrice(stopPrice)
                        .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                        .originalAmount(originalAmount)
                        .build());

        System.out.println("Placed Stop Loss" + bidType + "for value" + stopPrice + "with order id :" + orderId);

    }

    public void placeTakeProfitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, Ticker ticker)
            throws IOException {
        BigDecimal stopPrice = ticker.getLast();
        stopPrice = stopPrice.setScale(0, RoundingMode.DOWN);
        String orderId = exchange.getTradeService()
                .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                        .intention(StopOrder.Intention.TAKE_PROFIT)
                        .stopPrice(stopPrice)
                        .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                        .originalAmount(originalAmount)
                        .build());

        System.out.println("Placed Take Profit" + bidType + "for value" + stopPrice + "with order id :" + orderId);

    }

    public void cancelTopFirstOrder(Instrument instrument) throws IOException {

        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        if (!openOrders.isEmpty()) {
            exchange.getTradeService().cancelOrder(openOrders.get(0).getId());
        }
        exchange.getTradeService().cancelAllOrders(new DefaultCancelAllOrdersByInstrument(instrument));

    }

}
