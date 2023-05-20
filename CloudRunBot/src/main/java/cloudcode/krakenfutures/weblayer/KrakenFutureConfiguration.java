/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package cloudcode.krakenfutures.weblayer;

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
import org.knowm.xchange.krakenfutures.KrakenFuturesExchange;
import org.knowm.xchange.krakenfutures.dto.marketData.KrakenFuturesTicker;
import org.knowm.xchange.krakenfutures.dto.trade.KrakenFuturesOpenPosition;
import org.knowm.xchange.krakenfutures.dto.trade.KrakenFuturesOrderFlags;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesMarketDataServiceRaw;
import org.knowm.xchange.krakenfutures.service.KrakenFuturesTradeServiceRaw;
import org.knowm.xchange.service.trade.params.DefaultCancelOrderByInstrumentAndIdParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @author vscode
 */
@Component
public class KrakenFutureConfiguration {

    public static final double SL = 0.5;
    private final Exchange exchange = createExchange();

    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;
//    @Autowired
//    CryptoWatchConfiguration cryptoWatchConfiguration;

    public Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
//        spec.setApiKey("9uJBCOFWib8xnSfGIUnK5WyoHkdvJ/n0soLSgcAKilKNmF289B4A3myC");
//        spec.setSecretKey("MOzKv4yBmJIOIyJJBLQFoanaHLYSssMRizOL4M8Kwg7UcPaFH9RCl26a8ViyE+JkR9iZXpf9GQ+mnnTWKZERiXBZ");
        spec.setApiKey("hp/R4xE5vE38ZZZ1vmgpX/ii5QX5VIQTVd97WS4d/zSAE2FizzaUCQMz");
        spec.setSecretKey("UAofzx1foXy+2xqNbt50Q4cPFy4Jllp+gyVlK6rzy6suWNirtPfy3VDVo4fdt5omRaPTn8J6V76uYoVy1sWbtC+u");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    public KrakenFuturesTicker getTickers(Instrument instrument) throws IOException {
        KrakenFuturesMarketDataServiceRaw marketDataService = (KrakenFuturesMarketDataServiceRaw) exchange
                .getMarketDataService();
        return marketDataService.getKrakenFuturesTicker(instrument);
    }

//    public void placeOrder1(Instrument instrument, BigDecimal originalAmount)
//            throws IOException {
//        System.out
//                .println("future" +
//                        cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice().getChange());
//        System.out.println("spot" +
//                cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getChange());
//        System.out.println(
//                "future price last" +
//                        cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice().getLast());
//        System.out.println(
//                "spot price last " +
//                        cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getLast());
//        BigDecimal priceDifference = cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getChange()
//                .getAbsolute().subtract(cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice()
//                        .getChange().getAbsolute());
//        BigDecimal predictedPrice = cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice().getLast()
//                .plus().add(priceDifference);
//        System.out.println("predictedfuture" + predictedPrice);
//        System.out.println("pricedfference" + priceDifference);
//    }

//    public BigDecimal getProfitLimitPrice(Instrument instrument) throws IOException {
//
//        BigDecimal predictedPrice = BigDecimal.ZERO;
//
//        BigDecimal futureBigDecimalPercentage = cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice()
//                .getChange().getPercentage();
//        BigDecimal spotBigDecimalPercentage = cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice()
//                .getChange().getPercentage();
//        BigDecimal priceDifference = cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getChange()
//                .getAbsolute().subtract(cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice()
//                        .getChange().getAbsolute());
//
//        KrakenFuturesTicker krakenFutureTicker = getTickers(instrument);
//        if (futureBigDecimalPercentage.max(spotBigDecimalPercentage).equals(futureBigDecimalPercentage)) {
//            if (priceDifference.compareTo(BigDecimal.ZERO) > 0)
//                predictedPrice = krakenFutureTicker.getMarkPrice().subtract(priceDifference);
//        } else {
//            predictedPrice = krakenFutureTicker.getMarkPrice().plus().add(priceDifference);
//        }
//        System.out.println("predictedfuture" + predictedPrice);
//        System.out.println("pricedfference" + priceDifference);
//        return predictedPrice;
//    }

    public void placeOrder(Instrument instrument, BigDecimal originalAmount, Rule buyRule, Rule sellRule,
                           BarSeries series) throws IOException {
        // checkAccount();
        List<OpenPosition> openPositionsList = getPositions();

        String triggerOrderType = "";

        BigDecimal openPositionAmount = BigDecimal.ZERO;

        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream()
                    .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                            .contains(instrument.getBase().getCurrencyCode()))
                    .findFirst().orElse(null);
            if (openPosition != null) {
                openPositionAmount = openPosition.getSize();
                if (openPosition.getType()
                        .equals(OpenPosition.Type.LONG)) {
                    triggerOrderType = "ASK";
                } else {
                    triggerOrderType = "BID";
                }
            }
        }
        checkOpenOrdersAndCancelFirst(instrument);
        BigDecimal krakenFutureLastValue = getTickers(instrument).getMarkPrice();
        BigDecimal krakenSpotLastValue = krakenSpotConfiguration.getKrakenSpotTicker(instrument).getClose().getPrice();


        if (buyRule.isSatisfied(series.getEndIndex())) {
            if (triggerOrderType.isEmpty())
                triggerOrderType = "ASK";

            if (!Objects.equals(openPositionAmount, BigDecimal.ZERO) && !triggerOrderType.equals("ASK")) {
                originalAmount = openPositionAmount;
            }
            String marketOrderId = placeMarketOrder(instrument, originalAmount, "BID",
                    krakenFutureLastValue,
                    openPositionsList);
            if (marketOrderId.isEmpty()) {
                placeLimitOrder(instrument, originalAmount, "BID", krakenFutureLastValue,
                        openPositionsList);
            }
            triggerOrders(instrument, originalAmount, openPositionsList, triggerOrderType,
                    krakenSpotLastValue);
        } else if (sellRule.isSatisfied(series.getEndIndex())) {
            if (triggerOrderType.isEmpty())
                triggerOrderType = "BID";

            if (!Objects.equals(openPositionAmount, BigDecimal.ZERO) && !triggerOrderType.equals("BID")) {
                originalAmount = openPositionAmount;
            }
            String marketOrderId = placeMarketOrder(instrument, originalAmount, "ASK",
                    krakenFutureLastValue,
                    openPositionsList);
            if (marketOrderId.isEmpty()) {
                placeLimitOrder(instrument, originalAmount, "ASK", krakenFutureLastValue,
                        openPositionsList);
            }
            triggerOrders(instrument, originalAmount, openPositionsList, triggerOrderType,
                    krakenSpotLastValue);
        } else {
            System.out.println("Initial Condition Failed!!");
            if (!triggerOrderType.isEmpty()) {
                placeStopOrder(instrument, originalAmount, triggerOrderType,
                        krakenSpotLastValue, openPositionsList);
            }
        }
    }

    private void triggerOrders(Instrument instrument, BigDecimal originalAmount, List<OpenPosition> openPositionsList,
                               String triggerOrderType, BigDecimal krakenSpotLastValue) throws IOException {

        placeStopOrder(instrument, originalAmount, triggerOrderType,
                krakenSpotLastValue, openPositionsList);
        // placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList,
        // triggerOrderType,
        // openPositionPrice,
        // profitLimitPricePredicted);
    }

    private void placeTakeProfitPostValidation(Instrument instrument, BigDecimal originalAmount,
                                               List<OpenPosition> openPositionsList,
                                               String triggerOrderType, BigDecimal openPositionPrice, BigDecimal krakenSpotLastValue) throws IOException {
        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                            .getCurrencyCode().contains(instrument.getBase().getCurrencyCode())).findAny()
                    .orElse(null);
            if (openPosition != null) {
                String type = openPosition.getType().name();
                if (krakenSpotLastValue.compareTo(openPositionPrice) > 0
                        && type.equals("LONG"))
                    placeTakeProfitOrder(instrument, originalAmount, triggerOrderType, krakenSpotLastValue,
                            openPositionsList);
                else if (krakenSpotLastValue.compareTo(openPositionPrice) < 0
                        && type.equals("SHORT")) {
                    placeTakeProfitOrder(instrument, originalAmount, triggerOrderType, krakenSpotLastValue,
                            openPositionsList);
                }
            } else {
                placeLimitOrder(instrument, originalAmount, triggerOrderType, krakenSpotLastValue, openPositionsList);
            }
        } else {
            placeLimitOrder(instrument, originalAmount, triggerOrderType, krakenSpotLastValue, openPositionsList);
        }
    }

    public String placeMarketOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
                                   List<OpenPosition> openPositionsList) {

        String orderId = "";
        boolean shouldBePlaced;
        BigDecimal limitPrice = price;

        limitPrice = priceDecimalPrecision(instrument, limitPrice);

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, limitPrice, instrument);

        try {
            System.out.println("Market Order should be placed:" + shouldBePlaced);

            if (shouldBePlaced) {
                orderId = exchange.getTradeService()
                        .placeMarketOrder(new MarketOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .flag(KrakenFuturesOrderFlags.POST_ONLY)
                                .originalAmount(originalAmount)
                                .build());

                System.out
                        .println("Placed Market Order for instrument" + instrument.getBase().getCurrencyCode() + bidType
                                + "with order id :" + orderId);
            }

        } catch (Exception e) {
            System.out.println("Inside Market Order Exception:" + e.getMessage());
        }
        return orderId;
    }

    /**
     * @param instrument
     * @param originalAmount
     * @param bidType
     * @param price
     * @param openPositionsList
     */

    public void placeLimitOrder(Instrument instrument, BigDecimal originalAmount, String bidType,
                                BigDecimal price,
                                List<OpenPosition> openPositionsList) {

        boolean shouldBePlaced;
        BigDecimal limitPrice = price;

        limitPrice = priceDecimalPrecision(instrument, limitPrice);

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, limitPrice, instrument);
        try {
            System.out.println("Limit Order should be placed:" + shouldBePlaced);
            if (shouldBePlaced) {
                Set<Order.IOrderFlags> orderFlags = new HashSet<>();
                orderFlags.add(KrakenFuturesOrderFlags.POST_ONLY);

                String orderId = exchange.getTradeService()
                        .placeLimitOrder(new LimitOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .limitPrice(limitPrice)
                                .flags(orderFlags)
                                .originalAmount(originalAmount)
                                .build());

                System.out
                        .println("Placed Limit Order for instrument" + instrument.getBase().getCurrencyCode() + bidType
                                + "for value"
                                + limitPrice + "with order id :"
                                + orderId);
            }

        } catch (Exception e) {
            System.out.println("Inside Exception Limit Order:" + e.getMessage());
        }
    }

    private boolean isAllowedTrade(String bidType, List<OpenPosition> openPositionsList,
                                   BigDecimal limitPrice, Instrument instrument) {
        BigDecimal openPositionPrice;
        boolean shouldBePlaced = true;
        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                            .getCurrencyCode().contains(instrument.getBase().getCurrencyCode())).findAny()
                    .orElse(null);
            if (openPosition != null) {
                openPositionPrice = openPosition.getPrice();
                String type = openPosition.getType().name();
                if (limitPrice.compareTo(openPositionPrice) > 0
                        && type.equals("SHORT")
                        && bidType.equals("BID")) {
                    shouldBePlaced = false;
                } else if (limitPrice.compareTo(openPositionPrice) < 0
                        && type.equals("LONG")
                        && bidType.equals("ASK")) {
                    shouldBePlaced = false;
                }
            }
        }

        return shouldBePlaced;
    }

    /**
     * @param instrument
     * @param originalAmount
     * @param bidType
     * @param price
     * @param openPositionsList
     */

    public void placeStopOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
                               List<OpenPosition> openPositionsList) {
        BigDecimal stopPrice;
        OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                .getCurrencyCode().contains(instrument.getBase().getCurrencyCode())).findAny().orElse(null);
        if (bidType.equals("BID")) {
            if (openPositionsList.size() > 0) {
                if (openPosition != null) {
                    price = openPosition.getPrice();
                    originalAmount = openPosition.getSize();
                }
            }
            stopPrice = price.plus().add(price.multiply(BigDecimal.valueOf(SL / 100.0)));
        } else {
            if (openPositionsList.size() > 0) {
                if (openPosition != null) {
                    price = openPosition.getPrice();
                }
            }
            stopPrice = price.subtract(price.multiply(BigDecimal.valueOf(SL / 100.0)));
        }

        stopPrice = priceDecimalPrecision(instrument, stopPrice);

        try {
            Set<Order.IOrderFlags> orderFlags = new HashSet<>();
            orderFlags.add(KrakenFuturesOrderFlags.REDUCE_ONLY);
            String orderId = exchange.getTradeService()
                    .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                            .intention(StopOrder.Intention.STOP_LOSS)
                            .stopPrice(stopPrice)
                            .flags(orderFlags)
                            .originalAmount(originalAmount)
                            .build());

            System.out.println("Placed Stop Loss instrument" + instrument.getBase().getCurrencyCode() + bidType
                    + "for value" + stopPrice
                    + "with order id :" + orderId);

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

    public void placeTakeProfitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
                                     List<OpenPosition> openPositionsList) {

        System.out.println("Inside Profit Order");

        boolean shouldBePlaced = true;

        BigDecimal positionSize;
        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                    .getCurrencyCode().contains(instrument.getBase().getCurrencyCode())).findAny().orElse(null);
            if (openPosition != null) {
                positionSize = openPosition.getSize();
            } else {
                positionSize = originalAmount;
            }
        } else {
            positionSize = originalAmount;
        }

        boolean isAllowedTrade = isAllowedTrade(bidType, openPositionsList, price, instrument);
        BigDecimal stopPrice = price;
        stopPrice = priceDecimalPrecision(instrument, stopPrice);
        try {
            System.out.println("Take Profit Order should be placed:" + shouldBePlaced);

            if (isAllowedTrade) {
                String orderId = exchange.getTradeService()
                        .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .intention(StopOrder.Intention.TAKE_PROFIT)
                                .limitPrice(stopPrice)
                                .stopPrice(stopPrice)
                                .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                                .originalAmount(positionSize)
                                .build());
                System.out.println(
                        "Placed Take Profit instrument" + instrument.getBase().getCurrencyCode() + bidType + "for value"
                                + stopPrice
                                + "with order id :" + orderId);
            }

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }

    }

    private BigDecimal priceDecimalPrecision(Instrument instrument, BigDecimal price) {
        if (instrument.getBase().getCurrencyCode().equals("BTC"))
            price = price.setScale(0, RoundingMode.DOWN);
        else if (instrument.getBase().getCurrencyCode().equals("MATIC")) {
            price = price.setScale(4, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("BCH")) {
            price = price.setScale(2, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("LTC")) {
            price = price.setScale(2, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("KSM")) {
            price = price.setScale(2, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("GMT")) {
            price = price.setScale(4, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("APE")) {
            price = price.setScale(3, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("ETH")) {
            price = price.setScale(1, RoundingMode.DOWN);
        } else if (instrument.getBase().getCurrencyCode().equals("ATOM")) {
            price = price.setScale(3, RoundingMode.DOWN);
        }
        return price;
    }

    public void cancelTopFirstOrder(Instrument instrument) throws IOException {

        List<LimitOrder> openOrders = exchange.getTradeService().getOpenOrders().getOpenOrders();
        if (!openOrders.isEmpty()) {
            exchange.getTradeService().cancelOrder(openOrders.get(0).getId());
        }
    }

    public void checkAccount() throws IOException {

        AccountInfo accountInfo = exchange.getAccountService().getAccountInfo();
        System.out.println(accountInfo);
        System.out.println(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING).toString());
        System.out.println(Objects.requireNonNull(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING))
                .getCurrentLeverage().toString());
    }

    public void checkOpenOrdersAndCancelFirst(Instrument instrument) throws IOException {
        OpenOrders openOrders = exchange.getTradeService().getOpenOrders();
        System.out.println("Inside Cancelling Orders");
        if (!openOrders.getAllOpenOrders().isEmpty()) {
            System.out.println("Before Cancelling Trigger Order the count was:" + openOrders.getAllOpenOrders().size());
            openOrders.getAllOpenOrders().stream()
                    .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                            .contains(instrument.getBase().getCurrencyCode()))
                    .forEach(arg0 -> {
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
