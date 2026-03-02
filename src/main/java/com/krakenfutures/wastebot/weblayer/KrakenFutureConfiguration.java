/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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

    private Exchange exchange;

    // Track the quantity used to open each position (key: instrument base currency)
    // This ensures we close positions with the exact same quantity used to open them
    private final Map<String, BigDecimal> positionQuantities = new ConcurrentHashMap<>();

    private Exchange getExchange() {
        if (exchange == null) {
            exchange = createExchange();
        }
        return exchange;
    }

    public KrakenFuturesTicker getFuturesPriceChange(Instrument instrument) throws IOException {
        return getTickers(instrument);
    }

    public KrakenTicker getSpotPriceChange(Instrument instrument) throws IOException {
        return krakenSpotConfiguration.getKrakenSpotTicker(instrument);
    }

    private Exchange createExchange() {
        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesExchange.class);
        
        // Use environment variables for API credentials (production-ready)
        String apiKey = System.getenv("KRAKEN_API_KEY");
        String secretKey = System.getenv("KRAKEN_SECRET_KEY");
        String sandboxEnabled = System.getenv("KRAKEN_SANDBOX_ENABLED");
        
        // Fallback to default sandbox keys if environment variables not set
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = "xcqetVdEl0DHHZuTXRWOY8xZudErAZgUUHE41PuLYStO3ggiiv+EIrGI";
        }
        if (secretKey == null || secretKey.isEmpty()) {
            secretKey = "bvqN0c8JSNS+cg/P6leDIb4uMVQVBts5mG2ZwhKXxB9N0n/17VdB0i5HqPO3gL91wbWcy2fGP83wMnl6Sxg/oP5K";
        }
        
        spec.setApiKey(apiKey);
        spec.setSecretKey(secretKey);
        
        // Use sandbox by default unless explicitly disabled
        boolean useSandbox = (sandboxEnabled == null) || "true".equalsIgnoreCase(sandboxEnabled);
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, useSandbox);
        
        return ExchangeFactory.INSTANCE.createExchange(spec);
    }

    public KrakenFuturesTicker getTickers(Instrument instrument) throws IOException {
        KrakenFuturesMarketDataServiceRaw marketDataService = (KrakenFuturesMarketDataServiceRaw) getExchange()
                .getMarketDataService();
        return marketDataService.getKrakenFuturesTicker(instrument);
    }

    // public void placeOrder(Instrument instrument, BigDecimal originalAmount)
    // throws IOException {
    // System.out
    // .println("future" +
    // cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice().getChange());
    // System.out.println("spot" +
    // cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getChange());
    // System.out.println(
    // "future price last" +
    // cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice().getLast());
    // System.out.println(
    // "spot price last " +
    // cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getLast());
    // BigDecimal priceDifference =
    // cryptoWatchConfiguration.getSpotPriceChange(instrument).getPrice().getChange()
    // .getAbsolute().subtract(cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice()
    // .getChange().getAbsolute());
    // BigDecimal predictedPrice =
    // cryptoWatchConfiguration.getFuturesPriceChange(instrument).getPrice().getLast()
    // .plus().add(priceDifference);
    // System.out.println("predictedfuture" + predictedPrice);
    // System.out.println("pricedfference" + priceDifference);
    // }

    public BigDecimal getProfitLimitPrice(Instrument instrument) throws IOException {

        BigDecimal predictedPrice;

        KrakenFuturesTicker futuresTicker = getFuturesPriceChange(instrument);
        KrakenTicker spotTicker = getSpotPriceChange(instrument);

        // Calculate percentage change from ticker data
        BigDecimal futuresLast = futuresTicker.getMarkPrice();
        BigDecimal futuresOpen = futuresTicker.getOpen24H();
        BigDecimal futureBigDecimalPercentage = futuresOpen.compareTo(BigDecimal.ZERO) > 0
            ? futuresLast.subtract(futuresOpen).divide(futuresOpen, 6, BigDecimal.ROUND_HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;

        BigDecimal spotLast = spotTicker.getClose().getPrice();
        BigDecimal spotOpen = spotTicker.getOpen();
        BigDecimal spotBigDecimalPercentage = spotOpen.compareTo(BigDecimal.ZERO) > 0
            ? spotLast.subtract(spotOpen).divide(spotOpen, 6, BigDecimal.ROUND_HALF_UP).multiply(BigDecimal.valueOf(100))
            : BigDecimal.ZERO;

        BigDecimal priceDifference;
        priceDifference = spotLast.subtract(futuresLast);

        System.out
                .println("future" + futureBigDecimalPercentage);
        System.out.println("spot" + spotBigDecimalPercentage);

        KrakenFuturesTicker krakenFutureTicker = getTickers(instrument);
        BigDecimal krakenFutureLastValue = krakenFutureTicker.getMarkPrice();

        System.out.println(
                "future price last" + krakenFutureLastValue);
        System.out.println(
                "spot price last " + spotLast);

        if (futureBigDecimalPercentage.max(spotBigDecimalPercentage) == futureBigDecimalPercentage) {
            if (priceDifference.compareTo(BigDecimal.ZERO) > 0)
                predictedPrice = krakenFutureTicker.getMarkPrice().subtract(priceDifference);
            predictedPrice = krakenFutureTicker.getMarkPrice().plus().add(priceDifference);
        } else {
            predictedPrice = krakenFutureTicker.getMarkPrice().plus().add(priceDifference);
        }
        System.out.println("predictedfuture" + predictedPrice);
        System.out.println("pricedfference" + priceDifference);
        return predictedPrice;
    }

    public void placeOrder(Instrument instrument, BigDecimal originalAmount) throws IOException {
        // checkAccount();
        List<OpenPosition> openPositionsList = getPositions();
        
        // Check if there's already an open position for this instrument
        // If yes, wait for it to be fully closed before opening a new one
        String instrumentKey = instrument.getBase().getCurrencyCode();
        OpenPosition existingPosition = openPositionsList.stream()
                .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                        .equals(instrument.getBase().getCurrencyCode()))
                .findFirst().orElse(null);
        
        if (existingPosition != null) {
            // Position already exists for this asset - use the tracked quantity to close it
            BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
            if (trackedQuantity != null) {
                originalAmount = trackedQuantity;
                System.out.println("Using tracked quantity " + originalAmount + " for existing position on " + instrumentKey);
            }
            
            // Skip placing new orders - we already have an open position for this asset
            // Wait for the existing position to be fully closed before opening a new one
            System.out.println("Position already exists for " + instrumentKey + ", waiting for it to be fully closed before opening new position");
            return;
        } else {
            // No existing position - this is a new position, store the quantity
            positionQuantities.put(instrumentKey, originalAmount);
            System.out.println("Stored opening quantity " + originalAmount + " for " + instrumentKey);
        }

        String triggerOrderType = "";

        BigDecimal openPositionPrice = BigDecimal.ZERO;

        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream()
                    .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                            .contains(instrument.getBase().getCurrencyCode()) == true)
                    .findFirst().orElse(null);
            if (openPosition != null) {
                openPositionPrice = openPosition.getPrice();
                if (openPosition.getType()
                        .equals(OpenPosition.Type.LONG)) {
                    triggerOrderType = "ASK";
                } else {
                    triggerOrderType = "BID";
                }
            } else {
                openPositionPrice = originalAmount;
            }
        }
        checkOpenOrdersandCancelFirst(instrument);
        KrakenFuturesTicker krakenFutureTicker = getTickers(instrument);
        KrakenTicker krakenSpotTicker = krakenSpotConfiguration.getKrakenSpotTicker(instrument);
        BigDecimal krakenFutureLastValue = krakenFutureTicker.getMarkPrice();
        BigDecimal krakenSpotLastValue = krakenSpotTicker.getAsk().getPrice();

        System.out.println("krakenFutureLastValue" + krakenFutureLastValue.toString());
        System.out.println("krakenSpotLastValue" + krakenSpotLastValue.toString());

        BigDecimal profitLimitPricePredicted = getProfitLimitPrice(instrument);

        if (krakenSpotLastValue.compareTo(krakenFutureLastValue) > 0) {
            if (triggerOrderType.isEmpty())
                triggerOrderType = "ASK";
            String marketOrderId = placeMarketOrder(instrument, originalAmount, "BID", krakenFutureLastValue,
                    openPositionsList);
            if (marketOrderId.isEmpty()) {
                placeLimitOrder(instrument, originalAmount, "BID", krakenFutureLastValue,
                        openPositionsList);
            }
            placeStopOrder(instrument, originalAmount, triggerOrderType,
                    krakenFutureLastValue, openPositionsList);
            placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList, triggerOrderType,
                    openPositionPrice,
                    profitLimitPricePredicted);
        } else if (krakenSpotLastValue.compareTo(krakenFutureLastValue) < 0) {
            if (triggerOrderType.isEmpty())
                triggerOrderType = "BID";
            String marketOrderId = placeMarketOrder(instrument, originalAmount, "ASK", krakenFutureLastValue,
                    openPositionsList);
            if (marketOrderId.isEmpty()) {
                placeLimitOrder(instrument, originalAmount, "ASK", krakenFutureLastValue,
                        openPositionsList);
            }
            placeStopOrder(instrument, originalAmount, triggerOrderType,
                    krakenSpotLastValue, openPositionsList);
            placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList, triggerOrderType,
                    openPositionPrice,
                    profitLimitPricePredicted);
        } else {
            // do nothing
        }
    }

    private void placeTakeProfitPostValidation(Instrument instrument, BigDecimal originalAmount,
            List<OpenPosition> openPositionsList,
            String triggerOrderType, BigDecimal openPositionPrice, BigDecimal krakenSpotLastValue) throws IOException {
        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                    .getCurrencyCode().contains(instrument.getBase().getCurrencyCode()) == true).findAny()
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
            List<OpenPosition> openPositionsList)
            throws IOException {

        String orderId = "";
        boolean shouldBePlaced = true;
        BigDecimal limitPrice = price;

        limitPrice = priceDecimalPrecision(instrument, limitPrice);

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, limitPrice, instrument);

        try {
            if (shouldBePlaced) {
                orderId = getExchange().getTradeService()
                        .placeMarketOrder(new MarketOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .originalAmount(originalAmount)
                                .build());

                System.out.println("Placed Market Order " + bidType + "with order id :" + orderId);
            }

        } catch (Exception e) {
            System.out.println("Inside Market Order Exception:" + e.getMessage());
        }
        return orderId;
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

        limitPrice = priceDecimalPrecision(instrument, limitPrice);

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, limitPrice, instrument);
        try {

            if (shouldBePlaced) {
                String orderId = getExchange().getTradeService()
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
            BigDecimal limitPrice, Instrument instrument) {
        BigDecimal openPositionPrice;
        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                    .getCurrencyCode().contains(instrument.getBase().getCurrencyCode()) == true).findAny()
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
     * 
     * @param instrument
     * @param originalAmount
     * @param bidType
     * @param price
     * @param openPositionsList
     * @throws IOException
     */

    public void placeStopOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
            List<OpenPosition> openPositionsList)
            throws IOException {
        BigDecimal stopPrice;
        OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                .getCurrencyCode().contains(instrument.getBase().getCurrencyCode()) == true).findAny().orElse(null);
        
        // Use the SAME quantity that was used to open the position
        String instrumentKey = instrument.getBase().getCurrencyCode();
        if (openPosition != null) {
            // Use the tracked quantity to ensure we close with the same amount used to open
            BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
            if (trackedQuantity != null) {
                originalAmount = trackedQuantity;
                System.out.println("Stop Order - Using tracked quantity: " + originalAmount);
            } else {
                // Fallback to position size if not tracked
                originalAmount = openPosition.getSize();
            }
        }
        
        if (bidType.equals("BID")) {
            if (openPositionsList.size() > 0) {
                if (openPosition != null) {
                    price = openPosition.getPrice();
                    stopPrice = price.plus().add(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
                }
                stopPrice = price.plus().add(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
            }
            stopPrice = price.plus().add(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
        } else {
            if (openPositionsList.size() > 0) {
                if (openPosition != null) {
                    price = openPosition.getPrice();
                    stopPrice = price.subtract(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
                }
                stopPrice = price.subtract(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));

            }
            stopPrice = price.subtract(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
        }

        stopPrice = priceDecimalPrecision(instrument, stopPrice);

        try {
            String orderId = getExchange().getTradeService()
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

        System.out.println("Inside Profit Order");

        boolean shouldBePlaced = true;

        BigDecimal positionSize = BigDecimal.ZERO;
        
        // Use the SAME quantity that was used to open the position
        String instrumentKey = instrument.getBase().getCurrencyCode();
        if (openPositionsList.size() > 0) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                    .getCurrencyCode().contains(instrument.getBase().getCurrencyCode()) == true).findAny().orElse(null);
            if (openPosition != null) {
                // Use the tracked quantity to ensure we close with the same amount used to open
                BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
                if (trackedQuantity != null) {
                    positionSize = trackedQuantity;
                    System.out.println("Take Profit Order - Using tracked quantity: " + positionSize);
                } else {
                    // Fallback to position size if not tracked
                    positionSize = openPosition.getSize();
                }
            } else {
                positionSize = originalAmount;
            }
        } else {
            positionSize = originalAmount;
        }

        boolean isAllowedTrade = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, price, instrument);
        BigDecimal stopPrice = price;
        stopPrice = priceDecimalPrecision(instrument, stopPrice);
        try {
            if (isAllowedTrade) {
                String orderId = getExchange().getTradeService()
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
        }
        else if (instrument.getBase().getCurrencyCode().equals("GMT")) {
            price = price.setScale(4, RoundingMode.DOWN);
        }
        return price;
    }

    public void cancelTopFirstOrder(Instrument instrument) throws IOException {

        List<LimitOrder> openOrders = getExchange().getTradeService().getOpenOrders().getOpenOrders();
        if (!openOrders.isEmpty()) {
            getExchange().getTradeService().cancelOrder(openOrders.get(0).getId());
        }
        // getExchange().getTradeService().cancelAllOrders(new
        // DefaultCancelAllOrdersByInstrument(instrument));

    }

    public void checkAccount() throws IOException {

        AccountInfo accountInfo = getExchange().getAccountService().getAccountInfo();
        System.out.println(accountInfo);
        System.out.println(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING).toString());
        System.out.println(Objects.requireNonNull(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING))
                .getCurrentLeverage().toString());
    }

    public void checkOpenOrdersandCancelFirst(Instrument instrument) throws IOException {
        OpenOrders openOrders = getExchange().getTradeService().getOpenOrders();
        System.out.println("Inside Cancelling Orders");
        if (!openOrders.getHiddenOrders().isEmpty()) {
            System.out.println("Before Cancelling Trigger Order the count was:" + openOrders.getHiddenOrders().size());
            openOrders.getHiddenOrders().stream()
                    .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                            .contains(instrument.getBase().getCurrencyCode()))
                    .forEach(arg0 -> {
                        try {
                            String orderId = arg0.getId();
                            getExchange().getTradeService()
                                    .cancelOrder(new DefaultCancelOrderByInstrumentAndIdParams(instrument, orderId));
                            System.out.println("Cancelled Order" + orderId);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }

        OpenOrders postOpenOrders = getExchange().getTradeService().getOpenOrders();

        System.out.println("After Cancelling Trigger Order the count is:" + postOpenOrders.getHiddenOrders().size());

    }

    public List<OpenPosition> getPositions() throws IOException {
        List<OpenPosition> openPositions = getExchange().getTradeService().getOpenPositions().getOpenPositions();
        
        // Clean up position quantities for positions that no longer exist
        if (openPositions.isEmpty()) {
            positionQuantities.clear();
            System.out.println("All positions closed, cleared tracked quantities");
        } else {
            // Remove tracking for positions that have been closed
            positionQuantities.keySet().retainAll(
                openPositions.stream()
                    .map(p -> p.getInstrument().getBase().getCurrencyCode())
                    .collect(java.util.stream.Collectors.toSet())
            );
        }
        
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

