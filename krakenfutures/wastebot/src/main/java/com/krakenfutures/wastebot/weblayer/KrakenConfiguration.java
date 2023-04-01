/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.stereotype.Component;

/**
 *
 * @author vscode
 */
@Component
public class KrakenConfiguration {

    public Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
        spec.setApiKey("8TfYWFiQi7rfNeWcGw43VBMn+6vUEX5aXJFpC+9d2t9HhPfi+wvWtm+n");
        spec.setSecretKey("485DCqYfbb2hK4gsbB7NwPHz4esjfT1K9vvFdrP0Wwaq4+Qk8wirhxJHvVYKGTlenSrNawhleF+u2MLm57/k4ghO");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    public Ticker getTickers(Instrument instrument) throws IOException {
        Exchange exchange = createExchange();
        KrakenFuturesMarketDataService marketDataService = (KrakenFuturesMarketDataService) exchange
                .getMarketDataService();
        return marketDataService.getTicker(instrument);
    }

    public void placeLimitOrder(Instrument instrument, BigDecimal originalAmount, String bidType) throws IOException {
        Exchange exchange = createExchange();
        Ticker ticker = getTickers(instrument);
        exchange.getTradeService().placeLimitOrder(new LimitOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                .limitPrice(ticker.getLast())
                .originalAmount(originalAmount)
                .build());
        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        System.out.println(openOrders.get(0).toString());
    }

    public void placeStopOrder(Instrument instrument, BigDecimal originalAmount, String bidType) throws IOException {
        Exchange exchange = createExchange();
        Ticker ticker = getTickers(instrument);
        BigDecimal stopPrice = ticker.getLast().subtract(ticker.getLast().multiply(BigDecimal.valueOf(1/100.0)));
        stopPrice = stopPrice.setScale(0, RoundingMode.DOWN);
        System.out.println(stopPrice);
        exchange.getTradeService().placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                .intention(StopOrder.Intention.STOP_LOSS)
                .stopPrice(stopPrice)
                .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                .originalAmount(originalAmount)
                .build());
        OpenOrders openOrders = exchange.getTradeService().getOpenOrders();
        System.out.println("HiddenOrders:" + openOrders.getHiddenOrders());
    }

}
