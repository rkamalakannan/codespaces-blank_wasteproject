/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.dto.Order;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.OpenPosition;
import org.knowm.xchange.dto.account.Wallet;
import org.knowm.xchange.dto.trade.LimitOrder;
import org.knowm.xchange.dto.trade.MarketOrder;
import org.knowm.xchange.dto.trade.OpenOrders;
import org.knowm.xchange.dto.trade.StopOrder;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.krakenfutures.KrakenFuturesExchange;
import org.knowm.xchange.krakenfutures.dto.marketData.KrakenFuturesTicker;
import org.knowm.xchange.krakenfutures.dto.trade.KrakenFuturesOpenPosition;
import org.knowm.xchange.krakenfutures.dto.trade.KrakenFuturesOrderFlags;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesMarketDataServiceRaw;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesTradeServiceRaw;
import org.knowm.xchange.service.trade.params.DefaultCancelAllOrdersByInstrument;
import org.knowm.xchange.service.trade.params.DefaultCancelOrderByInstrumentAndIdParams;
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

    @Autowired
    BinanceFutureConfiguration binanceFutureConfiguration;

    private Exchange exchange = createExchange();

    public Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
        spec.setApiKey("8TfYWFiQi7rfNeWcGw43VBMn+6vUEX5aXJFpC+9d2t9HhPfi+wvWtm+n");
        spec.setSecretKey("485DCqYfbb2hK4gsbB7NwPHz4esjfT1K9vvFdrP0Wwaq4+Qk8wirhxJHvVYKGTlenSrNawhleF+u2MLm57/k4ghO");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    public KrakenFuturesTicker getTickers(Instrument instrument) throws IOException {
        KrakenFuturesMarketDataServiceRaw marketDataService = (KrakenFuturesMarketDataServiceRaw) exchange
                .getMarketDataService();
        return marketDataService.getKrakenFuturesTicker(instrument);
    }

    public void placeOrder(Instrument instrument, BigDecimal originalAmount) throws IOException {

        // KrakenAssetPairs krakenAssetPairs =
        // krakenSpotConfiguration.getKrakenAssetPairs(instrument);

        // int scaleLevel = krakenAssetPairs.getAssetPairMap()
        // .get(instrument.getBase() +
        // instrument.getCounter().getCurrencyCode()).getPairScale();
        // // KrakenFuturesInstrument krakenFuturesInstrument =
        // // marketDataService.getKrakenFuturesInstruments()
        // // .getInstruments().stream().filter(arg0 ->
        // // arg0.getSymbol().equals(instrument.getBase()))
        // // .findAny().get();
        // InstrumentMetaData metaData =
        // exchange.getExchangeMetaData().getInstruments().get(instrument);

        // BinanceFundingRate binancePrice =
        // binanceFutureConfiguration.getBinanceFutureTicker();
        checkAccount();
        List<OpenPosition> openPositionsList = getPositions();

        String triggerOrderType = "";

        BigDecimal openPositionPrice = BigDecimal.ZERO;

        if (openPositionsList.size() > 0) {
            openPositionPrice = openPositionsList.get(0).getPrice();
            if (openPositionsList.get(0).getType().equals(OpenPosition.Type.LONG)) {
                triggerOrderType = "ASK";
            } else {
                triggerOrderType = "BID";
            }
        }

        checkOpenOrdersandCancelFirst(instrument);

        KrakenFuturesTicker krakenFutureTicker = getTickers(instrument);
        KrakenTicker krakenSpotTicker = krakenSpotConfiguration.getKrakenSpotTicker(instrument);

        BigDecimal krakenFutureLastValue = krakenFutureTicker.getMarkPrice();
        // .setScale(krakenFuturesInstrument.getVolumeScale());
        BigDecimal krakenSpotLastValue = krakenSpotTicker.getClose().getPrice();
        // .setScale(krakenFuturesInstrument.getVolumeScale());
        // originalAmount = orderValuesHelper.adjustAmount(originalAmount);

        System.out.println("krakenFutureLastValue" + krakenFutureLastValue.toString());
        System.out.println("krakenSpotLastValue" + krakenSpotLastValue.toString());
        // System.out.println("Binance Future Last Value" +
        // binancePrice.getMarkPrice().toString());

        if (krakenSpotLastValue.compareTo(krakenFutureLastValue) > 0) {
            if (triggerOrderType.isEmpty())
                triggerOrderType = "ASK";
            placeMarketOrder(instrument, originalAmount, "BID", krakenFutureLastValue, openPositionsList);
            // placeLimitOrder(instrument, originalAmount, "BID", krakenFutureLastValue,
            // openPositionsList);
            placeStopOrder(instrument, originalAmount, triggerOrderType,
                    krakenFutureLastValue);
            placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList, triggerOrderType,
                    openPositionPrice,
                    krakenSpotLastValue);
        } else if (krakenSpotLastValue.compareTo(krakenFutureLastValue) < 0) {
            if (triggerOrderType.isEmpty())
                triggerOrderType = "BID";
            placeMarketOrder(instrument, originalAmount, "ASK", krakenFutureLastValue, openPositionsList);
            // placeLimitOrder(instrument, originalAmount, "ASK", krakenFutureLastValue,
            // openPositionsList);
            placeStopOrder(instrument, originalAmount, triggerOrderType,
                    krakenSpotLastValue);
            placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList, triggerOrderType,
                    openPositionPrice,
                    krakenSpotLastValue);
        } else {
            // do nothing
        }
    }

    private void placeTakeProfitPostValidation(Instrument instrument, BigDecimal originalAmount,
            List<OpenPosition> openPositionsList,
            String triggerOrderType, BigDecimal openPositionPrice, BigDecimal krakenSpotLastValue) throws IOException {
        if (openPositionsList.size() > 0) {
            if (krakenSpotLastValue.compareTo(openPositionPrice) > 0
                    && openPositionsList.get(0).getType().equals(OpenPosition.Type.LONG))
                placeTakeProfitOrder(instrument, originalAmount, triggerOrderType, krakenSpotLastValue,
                        openPositionsList);
            else if (krakenSpotLastValue.compareTo(openPositionPrice) < 0
                    && openPositionsList.get(0).getType().equals(OpenPosition.Type.SHORT)) {
                placeTakeProfitOrder(instrument, originalAmount, triggerOrderType, krakenSpotLastValue,
                        openPositionsList);
            }
        }
    }

    public void placeMarketOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
            List<OpenPosition> openPositionsList)
            throws IOException {

        boolean shouldBePlaced = true;
        BigDecimal limitPrice = price;
        if (instrument.getBase().getCurrencyCode().equals("BTC"))
            limitPrice = limitPrice.setScale(0, RoundingMode.DOWN);
        else if (instrument.getBase().getCurrencyCode().equals("MATIC")) {
            limitPrice = limitPrice.setScale(4, RoundingMode.DOWN);
        }

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, limitPrice);

        try {
            if (shouldBePlaced) {
                String orderId = exchange.getTradeService()
                        .placeMarketOrder(new MarketOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .originalAmount(originalAmount)
                                .build());

                System.out.println("Placed Market Order " + bidType + "with order id :" + orderId);
            }

        } catch (Exception e) {
            System.out.println("Inside Market Order Exception:" + e.getMessage());
        }

    }

    /**
     * 
     * @param instrument
     * @param originalAmount
     * @param bidType
     * @param price
     * @param openPositionsList
     * @throws IOException
     *                     if short position:
     *                     BID cannot be higher than the open position price
     *                     if LONG position:
     *                     ASK cannot be lesser than the open position price
     */

    public void placeLimitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
            List<OpenPosition> openPositionsList)
            throws IOException {

        boolean shouldBePlaced = true;
        BigDecimal limitPrice = price;
        if (instrument.getBase().getCurrencyCode().equals("BTC"))
            limitPrice = limitPrice.setScale(0, RoundingMode.DOWN);
        else if (instrument.getBase().getCurrencyCode().equals("MATIC")) {
            limitPrice = limitPrice.setScale(4, RoundingMode.DOWN);
        }

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, limitPrice);
        try {

            if (shouldBePlaced) {
                String orderId = exchange.getTradeService()
                        .placeLimitOrder(new LimitOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .limitPrice(limitPrice)
                                .originalAmount(originalAmount)
                                .build());
                System.out
                        .println("Placed Limit Order " + bidType + "for value" + limitPrice + "with order id :"
                                + orderId);
            }

        } catch (Exception e) {
            System.out.println("Inside Exception Limit Order:" + e.getMessage());
        }
    }

    private boolean isAllowedTrade(String bidType, List<OpenPosition> openPositionsList, boolean shouldBePlaced,
            BigDecimal limitPrice) {
        BigDecimal openPositionPrice;
        if (openPositionsList.size() > 0) {
            openPositionPrice = openPositionsList.get(0).getPrice();
            if (limitPrice.compareTo(openPositionPrice) > 0
                    && openPositionsList.get(0).getType().equals(OpenPosition.Type.SHORT) && bidType.equals("BID")) {
                shouldBePlaced = false;
            } else if (limitPrice.compareTo(openPositionPrice) < 0
                    && openPositionsList.get(0).getType().equals(OpenPosition.Type.LONG) && bidType.equals("ASK")) {
                shouldBePlaced = false;
            }
        }
        return shouldBePlaced;
    }

    public void placeStopOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price)
            throws IOException {
        BigDecimal stopPrice;
        if (bidType.equals("BID")) {
            stopPrice = price.plus().add(price.multiply(BigDecimal.valueOf(0.05 / 100.0)));
        } else {
            stopPrice = price.subtract(price.multiply(BigDecimal.valueOf(0.05 / 100.0)));
        }
        if (instrument.getBase().getCurrencyCode().equals("BTC"))
            stopPrice = stopPrice.setScale(0, RoundingMode.DOWN);
        else if (instrument.getBase().getCurrencyCode().equals("MATIC")) {
            stopPrice = stopPrice.setScale(4, RoundingMode.DOWN);
        }

        try {
            String orderId = exchange.getTradeService()
                    .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                            .intention(StopOrder.Intention.STOP_LOSS)
                            .stopPrice(stopPrice)
                            .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                            .originalAmount(originalAmount)
                            .build());

            System.out.println("Placed Stop Loss" + bidType + "for value" + stopPrice + "with order id :" + orderId);

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

    public void placeTakeProfitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
            List<OpenPosition> openPositionsList)
            throws IOException {

        boolean shouldBePlaced = true;

        BigDecimal positionSize = BigDecimal.ZERO;
        if (openPositionsList.size() > 0) {
            positionSize = openPositionsList.get(0).getSize();
        } else {
            positionSize = originalAmount;
        }

        boolean isAllowedTrade = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, price);
        BigDecimal stopPrice = price;
        if (instrument.getBase().getCurrencyCode().equals("BTC"))
            stopPrice = stopPrice.setScale(0, RoundingMode.DOWN);
        else if (instrument.getBase().getCurrencyCode().equals("MATIC")) {
            stopPrice = stopPrice.setScale(4, RoundingMode.DOWN);
        }
        try {
            if (isAllowedTrade) {
                String orderId = exchange.getTradeService()
                        .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .intention(StopOrder.Intention.TAKE_PROFIT)
                                .stopPrice(stopPrice)
                                .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                                .originalAmount(positionSize)
                                .build());

                System.out.println(
                        "Placed Take Profit" + bidType + "for value" + stopPrice + "with order id :" + orderId);
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

    public void cancelTopFirstOrder(Instrument instrument) throws IOException {

        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        if (!openOrders.isEmpty()) {
            exchange.getTradeService().cancelOrder(openOrders.get(0).getId());
        }
        exchange.getTradeService().cancelAllOrders(new DefaultCancelAllOrdersByInstrument(instrument));

    }

    public void checkAccount() throws IOException {

        AccountInfo accountInfo = exchange.getAccountService().getAccountInfo();
        System.out.println(accountInfo);
        System.out.println(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING).toString());
        System.out.println(Objects.requireNonNull(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING))
                .getCurrentLeverage().toString());
    }

    public void checkOpenOrdersandCancelFirst(Instrument instrument) throws IOException {
        OpenOrders openOrders = exchange.getTradeService().getOpenOrders();
        // System.out.println(openOrders.getHiddenOrders());
        // System.out.println(openOrders.getHiddenOrders().get(0).getInstrument());
        // System.out.println(openOrders.getHiddenOrders().get(0).getId());
        // System.out.println(openOrders.getHiddenOrders().get(0).hasFlag(KrakenFuturesOrderFlags.REDUCE_ONLY));

        // exchange.getTradeService().cancelAllOrders(new
        // DefaultCancelAllOrdersByInstrument(instrument));

        System.out.println("Inside Cancelling Orders");

        if (!openOrders.getHiddenOrders().isEmpty()) {
            System.out.println("Before Cancelling Trigger Order the count was:" + openOrders.getHiddenOrders().size());
            openOrders.getHiddenOrders().stream().forEach(arg0 -> {
                try {
                    String orderId = arg0.getId();
                    exchange.getTradeService()
                            .cancelOrder(new DefaultCancelOrderByInstrumentAndIdParams(instrument, orderId));
                    System.out.println("Cancelled Order" + orderId);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }

        OpenOrders postOpenOrders = exchange.getTradeService().getOpenOrders();

        System.out.println("After Cancelling Trigger Order the count is:" + postOpenOrders.getHiddenOrders().size());

    }

    public List<OpenPosition> getPositions() throws IOException {
        List<OpenPosition> openPositions = exchange.getTradeService().getOpenPositions().getOpenPositions();
        for (OpenPosition openPosition : openPositions) {
            System.out.println(openPosition);
        }
        return openPositions;
    }

    public List<KrakenFuturesOpenPosition> getPositionsRaw() throws IOException {

        KrakenFuturesTradeServiceRaw tradeServiceRaw = (KrakenFuturesTradeServiceRaw) exchange
                .getTradeService();
        List<KrakenFuturesOpenPosition> openPositions = tradeServiceRaw.getKrakenFuturesOpenPositions()
                .getOpenPositions();
        for (KrakenFuturesOpenPosition openPosition : openPositions) {
            System.out.println(openPosition);
        }
        return openPositions;
    }

}
