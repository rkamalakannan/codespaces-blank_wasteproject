/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package com.krakenfutures.wastebot.weblayer;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author vscode
 */
@Component
public class KrakenFutureConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(KrakenFutureConfiguration.class);

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

        logger.info("[PROFIT_LIMIT] futures%={}, spot%={}", futureBigDecimalPercentage, spotBigDecimalPercentage);

        KrakenFuturesTicker krakenFutureTicker = getTickers(instrument);
        BigDecimal krakenFutureLastValue = krakenFutureTicker.getMarkPrice();

        logger.info("[PROFIT_LIMIT] futures mark price={}, spot last={}", krakenFutureLastValue, spotLast);

        if (futureBigDecimalPercentage.max(spotBigDecimalPercentage) == futureBigDecimalPercentage) {
            if (priceDifference.compareTo(BigDecimal.ZERO) > 0)
                predictedPrice = krakenFutureTicker.getMarkPrice().subtract(priceDifference);
            predictedPrice = krakenFutureTicker.getMarkPrice().plus().add(priceDifference);
        } else {
            predictedPrice = krakenFutureTicker.getMarkPrice().plus().add(priceDifference);
        }
        logger.info("[PROFIT_LIMIT] predictedPrice={}, priceDifference={}", predictedPrice, priceDifference);
        return predictedPrice;
    }

    /**
     * Result class for order placement operations
     */
    public static class OrderResult {
        private final boolean success;
        private final String message;

        public OrderResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Check if an open position exists for the given instrument.
     * This is the PRIMARY GUARD that must be called before any order placement.
     *
     * @param instrument the trading instrument
     * @return OrderResult with error message if position exists, success otherwise
     */
    public OrderResult validateOrderPlacement(Instrument instrument) throws IOException {
        String instrumentKey = instrument.getBase().getCurrencyCode();
        logger.info("[VALIDATE] Checking open positions for instrument: {}", instrumentKey);

        List<OpenPosition> openPositionsList = getPositions();
        logger.info("[VALIDATE] Total open positions found: {}", openPositionsList.size());

        if (!openPositionsList.isEmpty()) {
            openPositionsList.forEach(p -> logger.info("[VALIDATE] Open position: instrument={}, type={}, size={}, price={}",
                    p.getInstrument().getBase().getCurrencyCode(),
                    p.getType(),
                    p.getSize(),
                    p.getPrice()));
        }

        OpenPosition existingPosition = openPositionsList.stream()
                .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                        .equals(instrumentKey))
                .findFirst().orElse(null);

        if (existingPosition != null) {
            BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
            BigDecimal positionSize = existingPosition.getSize();
            String positionType = existingPosition.getType().name();
            String errorMsg = "BLOCKED: Cannot place new order for " + instrumentKey +
                    ". An open position already exists. " +
                    "Position type: " + positionType +
                    ", Size: " + positionSize +
                    ", Tracked quantity: " + (trackedQuantity != null ? trackedQuantity : "N/A") +
                    ". Please wait for the existing position to be fully closed before opening a new one.";
            logger.warn("[VALIDATE] {}", errorMsg);
            return new OrderResult(false, errorMsg);
        }

        logger.info("[VALIDATE] No open position found for {}. Validation passed - order placement allowed.", instrumentKey);
        return new OrderResult(true, "Validation passed");
    }

    /**
     * Get current open position for an instrument
     *
     * @param instrument the trading instrument
     * @return OpenPosition if exists, null otherwise
     */
    public OpenPosition getOpenPosition(Instrument instrument) throws IOException {
        List<OpenPosition> openPositionsList = getPositions();
        return openPositionsList.stream()
                .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                        .equals(instrument.getBase().getCurrencyCode()))
                .findFirst().orElse(null);
    }

    public OrderResult placeOrder(Instrument instrument, BigDecimal originalAmount) throws IOException {
        String instrumentKey = instrument.getBase().getCurrencyCode();
        logger.info("[PLACE_ORDER] Attempting to place order for instrument: {}, amount: {}", instrumentKey, originalAmount);

        // CRITICAL GUARD: Re-check open positions immediately before placing any order.
        // This is a second layer of protection in addition to validateOrderPlacement()
        // called in ScheduledTradingService.processAsset().
        List<OpenPosition> openPositionsList = getPositions();
        logger.info("[PLACE_ORDER] Open positions at time of order placement for {}: {} total positions",
                instrumentKey, openPositionsList.size());

        OpenPosition existingPosition = openPositionsList.stream()
                .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                        .equals(instrumentKey))
                .findFirst().orElse(null);

        if (existingPosition != null) {
            // Position already exists for this asset - BLOCK new order placement
            BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
            BigDecimal positionSize = existingPosition.getSize();
            String positionType = existingPosition.getType().name();
            String errorMessage = "BLOCKED: Cannot place new order for " + instrumentKey +
                    ". An open position already exists. " +
                    "Position type: " + positionType +
                    ", Size: " + positionSize +
                    ", Tracked quantity: " + (trackedQuantity != null ? trackedQuantity : "N/A") +
                    ". Please wait for the existing position to be fully closed before opening a new one.";

            logger.warn("[PLACE_ORDER] {}", errorMessage);
            return new OrderResult(false, errorMessage);
        }

        // No existing position - this is a new position, store the quantity for tracking
        positionQuantities.put(instrumentKey, originalAmount);
        logger.info("[PLACE_ORDER] No existing position for {}. Stored opening quantity: {}", instrumentKey, originalAmount);

        String triggerOrderType = "";
        BigDecimal openPositionPrice = BigDecimal.ZERO;

        // openPositionsList is empty here (we verified no existing position above),
        // so this block is for future reference if logic changes
        if (!openPositionsList.isEmpty()) {
            OpenPosition openPosition = openPositionsList.stream()
                    .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                            .contains(instrumentKey))
                    .findFirst().orElse(null);
            if (openPosition != null) {
                openPositionPrice = openPosition.getPrice();
                if (openPosition.getType().equals(OpenPosition.Type.LONG)) {
                    triggerOrderType = "ASK";
                } else {
                    triggerOrderType = "BID";
                }
                logger.info("[PLACE_ORDER] Existing position found (unexpected): type={}, price={}", triggerOrderType, openPositionPrice);
            } else {
                openPositionPrice = originalAmount;
            }
        }

        checkOpenOrdersandCancelFirst(instrument);
        KrakenFuturesTicker krakenFutureTicker = getTickers(instrument);
        KrakenTicker krakenSpotTicker = krakenSpotConfiguration.getKrakenSpotTicker(instrument);
        BigDecimal krakenFutureLastValue = krakenFutureTicker.getMarkPrice();
        BigDecimal krakenSpotLastValue = krakenSpotTicker.getAsk().getPrice();

        logger.info("[PLACE_ORDER] {} - Futures mark price: {}, Spot ask price: {}",
                instrumentKey, krakenFutureLastValue, krakenSpotLastValue);

        BigDecimal profitLimitPricePredicted = getProfitLimitPrice(instrument);
        logger.info("[PLACE_ORDER] {} - Predicted profit limit price: {}", instrumentKey, profitLimitPricePredicted);

        if (krakenSpotLastValue.compareTo(krakenFutureLastValue) > 0) {
            logger.info("[PLACE_ORDER] {} - Spot > Futures: placing BID (long) market/limit order", instrumentKey);
            if (triggerOrderType.isEmpty())
                triggerOrderType = "ASK";
            String marketOrderId = placeMarketOrder(instrument, originalAmount, "BID", krakenFutureLastValue,
                    openPositionsList);
            if (marketOrderId.isEmpty()) {
                logger.info("[PLACE_ORDER] {} - Market order not placed, falling back to limit order", instrumentKey);
                placeLimitOrder(instrument, originalAmount, "BID", krakenFutureLastValue,
                        openPositionsList);
            }
            placeStopOrder(instrument, originalAmount, triggerOrderType,
                    krakenFutureLastValue, openPositionsList);
            placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList, triggerOrderType,
                    openPositionPrice,
                    profitLimitPricePredicted);
        } else if (krakenSpotLastValue.compareTo(krakenFutureLastValue) < 0) {
            logger.info("[PLACE_ORDER] {} - Spot < Futures: placing ASK (short) market/limit order", instrumentKey);
            if (triggerOrderType.isEmpty())
                triggerOrderType = "BID";
            String marketOrderId = placeMarketOrder(instrument, originalAmount, "ASK", krakenFutureLastValue,
                    openPositionsList);
            if (marketOrderId.isEmpty()) {
                logger.info("[PLACE_ORDER] {} - Market order not placed, falling back to limit order", instrumentKey);
                placeLimitOrder(instrument, originalAmount, "ASK", krakenFutureLastValue,
                        openPositionsList);
            }
            placeStopOrder(instrument, originalAmount, triggerOrderType,
                    krakenSpotLastValue, openPositionsList);
            placeTakeProfitPostValidation(instrument, originalAmount, openPositionsList, triggerOrderType,
                    openPositionPrice,
                    profitLimitPricePredicted);
        } else {
            logger.info("[PLACE_ORDER] {} - Spot == Futures price. No order placed.", instrumentKey);
        }
        return new OrderResult(true, "Order placement completed for " + instrumentKey);
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

        String instrumentKey = instrument.getBase().getCurrencyCode();
        String orderId = "";
        boolean shouldBePlaced = true;
        BigDecimal limitPrice = price;

        limitPrice = priceDecimalPrecision(instrument, limitPrice);

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, limitPrice, instrument);

        logger.info("[MARKET_ORDER] {} - bidType={}, price={}, amount={}, shouldBePlaced={}",
                instrumentKey, bidType, limitPrice, originalAmount, shouldBePlaced);

        try {
            if (shouldBePlaced) {
                orderId = getExchange().getTradeService()
                        .placeMarketOrder(new MarketOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .originalAmount(originalAmount)
                                .build());

                logger.info("[MARKET_ORDER] {} - Placed Market Order type={}, orderId={}", instrumentKey, bidType, orderId);
            } else {
                logger.warn("[MARKET_ORDER] {} - Market order NOT placed (isAllowedTrade=false) for bidType={}", instrumentKey, bidType);
            }

        } catch (Exception e) {
            logger.error("[MARKET_ORDER] {} - Exception placing market order: {}", instrumentKey, e.getMessage(), e);
        }
        return orderId;
    }

    /**
     * @param instrument
     * @param originalAmount
     * @param bidType
     * @param price
     * @param openPositionsList
     * @throws IOException if short position:
     *                     BID cannot be higher than the open position price
     *                     if LONG position:
     *                     ASK cannot be lesser than the open position price
     */

    public void placeLimitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
                                List<OpenPosition> openPositionsList)
            throws IOException {

        String instrumentKey = instrument.getBase().getCurrencyCode();
        boolean shouldBePlaced = true;
        BigDecimal limitPrice = price;

        limitPrice = priceDecimalPrecision(instrument, limitPrice);

        shouldBePlaced = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, limitPrice, instrument);

        logger.info("[LIMIT_ORDER] {} - bidType={}, price={}, amount={}, shouldBePlaced={}",
                instrumentKey, bidType, limitPrice, originalAmount, shouldBePlaced);

        try {
            if (shouldBePlaced) {
                String orderId = getExchange().getTradeService()
                        .placeLimitOrder(new LimitOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .limitPrice(limitPrice)
                                .originalAmount(originalAmount)
                                .build());
                logger.info("[LIMIT_ORDER] {} - Placed Limit Order type={}, price={}, orderId={}",
                        instrumentKey, bidType, limitPrice, orderId);
            } else {
                logger.warn("[LIMIT_ORDER] {} - Limit order NOT placed (isAllowedTrade=false) for bidType={}", instrumentKey, bidType);
            }

        } catch (Exception e) {
            logger.error("[LIMIT_ORDER] {} - Exception placing limit order: {}", instrumentKey, e.getMessage(), e);
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
        String instrumentKey = instrument.getBase().getCurrencyCode();
        BigDecimal stopPrice;
        OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                .getCurrencyCode().contains(instrumentKey)).findAny().orElse(null);

        // Use the SAME quantity that was used to open the position
        if (openPosition != null) {
            // Use the tracked quantity to ensure we close with the same amount used to open
            BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
            if (trackedQuantity != null) {
                originalAmount = trackedQuantity;
                logger.info("[STOP_ORDER] {} - Using tracked quantity: {}", instrumentKey, originalAmount);
            } else {
                // Fallback to position size if not tracked
                originalAmount = openPosition.getSize();
                logger.warn("[STOP_ORDER] {} - No tracked quantity found, using position size: {}", instrumentKey, originalAmount);
            }
        }

        if (bidType.equals("BID")) {
            if (!openPositionsList.isEmpty() && openPosition != null) {
                price = openPosition.getPrice();
                logger.info("[STOP_ORDER] {} - BID stop: using open position price: {}", instrumentKey, price);
            }
            stopPrice = price.plus().add(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
        } else {
            if (!openPositionsList.isEmpty() && openPosition != null) {
                price = openPosition.getPrice();
                logger.info("[STOP_ORDER] {} - ASK stop: using open position price: {}", instrumentKey, price);
            }
            stopPrice = price.subtract(price.multiply(BigDecimal.valueOf(0.1 / 100.0)));
        }

        stopPrice = priceDecimalPrecision(instrument, stopPrice);
        logger.info("[STOP_ORDER] {} - bidType={}, stopPrice={}, amount={}", instrumentKey, bidType, stopPrice, originalAmount);

        try {
            String orderId = getExchange().getTradeService()
                    .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                            .intention(StopOrder.Intention.STOP_LOSS)
                            .stopPrice(stopPrice)
                            .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                            .originalAmount(originalAmount)
                            .build());

            logger.info("[STOP_ORDER] {} - Placed Stop Loss type={}, stopPrice={}, orderId={}",
                    instrumentKey, bidType, stopPrice, orderId);

        } catch (Exception e) {
            logger.error("[STOP_ORDER] {} - Exception placing stop order: {}", instrumentKey, e.getMessage(), e);
        }

    }

    public void placeTakeProfitOrder(Instrument instrument, BigDecimal originalAmount, String bidType, BigDecimal price,
                                     List<OpenPosition> openPositionsList)
            throws IOException {

        String instrumentKey = instrument.getBase().getCurrencyCode();
        logger.info("[TAKE_PROFIT] {} - Entering take profit order placement, bidType={}, price={}", instrumentKey, bidType, price);

        boolean shouldBePlaced = true;
        BigDecimal positionSize = BigDecimal.ZERO;

        // Use the SAME quantity that was used to open the position
        if (!openPositionsList.isEmpty()) {
            OpenPosition openPosition = openPositionsList.stream().filter(arg0 -> arg0.getInstrument().getBase()
                    .getCurrencyCode().contains(instrumentKey)).findAny().orElse(null);
            if (openPosition != null) {
                // Use the tracked quantity to ensure we close with the same amount used to open
                BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
                if (trackedQuantity != null) {
                    positionSize = trackedQuantity;
                    logger.info("[TAKE_PROFIT] {} - Using tracked quantity: {}", instrumentKey, positionSize);
                } else {
                    // Fallback to position size if not tracked
                    positionSize = openPosition.getSize();
                    logger.warn("[TAKE_PROFIT] {} - No tracked quantity found, using position size: {}", instrumentKey, positionSize);
                }
            } else {
                positionSize = originalAmount;
                logger.info("[TAKE_PROFIT] {} - No matching open position, using original amount: {}", instrumentKey, positionSize);
            }
        } else {
            positionSize = originalAmount;
            logger.info("[TAKE_PROFIT] {} - No open positions, using original amount: {}", instrumentKey, positionSize);
        }

        boolean isAllowedTrade = isAllowedTrade(bidType, openPositionsList, shouldBePlaced, price, instrument);
        BigDecimal stopPrice = price;
        stopPrice = priceDecimalPrecision(instrument, stopPrice);

        logger.info("[TAKE_PROFIT] {} - bidType={}, stopPrice={}, positionSize={}, isAllowedTrade={}",
                instrumentKey, bidType, stopPrice, positionSize, isAllowedTrade);

        try {
            if (isAllowedTrade) {
                String orderId = getExchange().getTradeService()
                        .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(bidType), instrument)
                                .intention(StopOrder.Intention.TAKE_PROFIT)
                                .stopPrice(stopPrice)
                                .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                                .originalAmount(positionSize)
                                .build());
                logger.info("[TAKE_PROFIT] {} - Placed Take Profit type={}, stopPrice={}, orderId={}",
                        instrumentKey, bidType, stopPrice, orderId);
            } else {
                logger.warn("[TAKE_PROFIT] {} - Take profit order NOT placed (isAllowedTrade=false) for bidType={}", instrumentKey, bidType);
            }

        } catch (Exception e) {
            logger.error("[TAKE_PROFIT] {} - Exception placing take profit order: {}", instrumentKey, e.getMessage(), e);
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
        }
        return price;
    }

    public void cancelTopFirstOrder(Instrument instrument) throws IOException {

        List<LimitOrder> openOrders = getExchange().getTradeService().getOpenOrders().getOpenOrders();
        if (!openOrders.isEmpty()) {
            logger.info("[CANCEL] Cancelling top first order: {}", openOrders.get(0).getId());
            getExchange().getTradeService().cancelOrder(openOrders.get(0).getId());
        }
    }

    public void checkAccount() throws IOException {

        AccountInfo accountInfo = getExchange().getAccountService().getAccountInfo();
        logger.info("[ACCOUNT] Account info: {}", accountInfo);
        logger.info("[ACCOUNT] Futures wallet: {}", accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING));
        logger.info("[ACCOUNT] Current leverage: {}",
                Objects.requireNonNull(accountInfo.getWallet(Wallet.WalletFeature.FUTURES_TRADING))
                        .getCurrentLeverage());
    }

    public void checkOpenOrdersandCancelFirst(Instrument instrument) throws IOException {
        String instrumentKey = instrument.getBase().getCurrencyCode();
        OpenOrders openOrders = getExchange().getTradeService().getOpenOrders();
        logger.info("[CANCEL_ORDERS] {} - Checking open orders before placing new order", instrumentKey);

        if (!openOrders.getHiddenOrders().isEmpty()) {
            logger.info("[CANCEL_ORDERS] {} - Found {} hidden/trigger orders. Cancelling all for this instrument.",
                    instrumentKey, openOrders.getHiddenOrders().size());
            openOrders.getHiddenOrders().stream()
                    .filter(arg0 -> arg0.getInstrument().getBase().getCurrencyCode()
                            .contains(instrumentKey))
                    .forEach(arg0 -> {
                        try {
                            String orderId = arg0.getId();
                            getExchange().getTradeService()
                                    .cancelOrder(new DefaultCancelOrderByInstrumentAndIdParams(instrument, orderId));
                            logger.info("[CANCEL_ORDERS] {} - Cancelled order: {}", instrumentKey, orderId);
                        } catch (IOException e) {
                            logger.error("[CANCEL_ORDERS] {} - Error cancelling order: {}", instrumentKey, e.getMessage(), e);
                        }
                    });
        } else {
            logger.info("[CANCEL_ORDERS] {} - No hidden/trigger orders to cancel.", instrumentKey);
        }

        OpenOrders postOpenOrders = getExchange().getTradeService().getOpenOrders();
        logger.info("[CANCEL_ORDERS] {} - After cancellation: {} hidden orders remaining.",
                instrumentKey, postOpenOrders.getHiddenOrders().size());
    }

    public List<OpenPosition> getPositions() throws IOException {
        List<OpenPosition> openPositions = getExchange().getTradeService().getOpenPositions().getOpenPositions();

        logger.info("[POSITIONS] Fetched {} open position(s) from exchange.", openPositions.size());

        // Clean up position quantities for positions that no longer exist
        if (openPositions.isEmpty()) {
            if (!positionQuantities.isEmpty()) {
                logger.info("[POSITIONS] All positions closed. Clearing tracked quantities: {}", positionQuantities);
                positionQuantities.clear();
            }
        } else {
            // Remove tracking for positions that have been closed
            java.util.Set<String> activeKeys = openPositions.stream()
                    .map(p -> p.getInstrument().getBase().getCurrencyCode())
                    .collect(java.util.stream.Collectors.toSet());
            positionQuantities.keySet().retainAll(activeKeys);

            openPositions.forEach(openPosition ->
                    logger.info("[POSITIONS] Open position: instrument={}, type={}, size={}, price={}",
                            openPosition.getInstrument().getBase().getCurrencyCode(),
                            openPosition.getType(),
                            openPosition.getSize(),
                            openPosition.getPrice())
            );
        }

        return openPositions;
    }

    public List<KrakenFuturesOpenPosition> getPositionsRaw() throws IOException {

        KrakenFuturesTradeServiceRaw tradeServiceRaw = (KrakenFuturesTradeServiceRaw) exchange
                .getTradeService();
        List<KrakenFuturesOpenPosition> openPositions = tradeServiceRaw.getKrakenFuturesOpenPositions()
                .getOpenPositions();
        for (KrakenFuturesOpenPosition openPosition : openPositions) {
            logger.info("[POSITIONS_RAW] {}", openPosition);
        }
        return openPositions;
    }

    /**
     * Cancel all unfilled open orders (both regular limit orders and hidden/trigger orders)
     * that are older than the specified expiry duration.
     *
     * This method is called by the scheduler to automatically clean up stale orders
     * that were never filled. The expiry period is configured via
     * {@code trading.order.expiry-ms} in application.properties or environment variables.
     *
     * @param orderExpiryMs maximum age in milliseconds for an open order before it is cancelled.
     *                      Pass 0 or negative to skip cancellation.
     * @return number of orders cancelled
     */
    public int cancelExpiredOpenOrders(long orderExpiryMs) throws IOException {
        if (orderExpiryMs <= 0) {
            logger.debug("[EXPIRY] Order expiry disabled (orderExpiryMs={}). Skipping.", orderExpiryMs);
            return 0;
        }

        long now = System.currentTimeMillis();
        long cutoffTime = now - orderExpiryMs;
        int cancelledCount = 0;

        logger.info("[EXPIRY] Checking for unfilled open orders older than {}ms ({} seconds).",
                orderExpiryMs, orderExpiryMs / 1000);

        OpenOrders openOrders = getExchange().getTradeService().getOpenOrders();

        // Cancel expired regular limit/market orders
        List<LimitOrder> limitOrders = openOrders.getOpenOrders();
        logger.info("[EXPIRY] Found {} regular open order(s) to check.", limitOrders.size());

        for (LimitOrder order : limitOrders) {
            java.util.Date orderTimestamp = order.getTimestamp();
            if (orderTimestamp == null) {
                logger.warn("[EXPIRY] Regular order {} has no timestamp - skipping expiry check.", order.getId());
                continue;
            }
            long orderAgeMs = now - orderTimestamp.getTime();
            if (orderTimestamp.getTime() < cutoffTime) {
                logger.info("[EXPIRY] Cancelling expired regular order: id={}, instrument={}, type={}, price={}, age={}ms ({}s)",
                        order.getId(),
                        order.getInstrument() != null ? order.getInstrument().toString() : "unknown",
                        order.getType(),
                        order.getLimitPrice(),
                        orderAgeMs,
                        orderAgeMs / 1000);
                try {
                    getExchange().getTradeService().cancelOrder(
                            new DefaultCancelOrderByInstrumentAndIdParams(order.getInstrument(), order.getId()));
                    cancelledCount++;
                    logger.info("[EXPIRY] Successfully cancelled regular order: {}", order.getId());
                } catch (Exception e) {
                    logger.error("[EXPIRY] Failed to cancel regular order {}: {}", order.getId(), e.getMessage(), e);
                }
            } else {
                logger.debug("[EXPIRY] Regular order {} is within expiry window (age={}ms, limit={}ms). Keeping.",
                        order.getId(), orderAgeMs, orderExpiryMs);
            }
        }

        // Cancel expired hidden/trigger orders (stop-loss, take-profit)
        // getHiddenOrders() returns List<? extends Order> in XChange 5.x
        List<? extends Order> hiddenOrders = openOrders.getHiddenOrders();
        logger.info("[EXPIRY] Found {} hidden/trigger order(s) to check.", hiddenOrders.size());

        for (Order order : hiddenOrders) {
            java.util.Date orderTimestamp = order.getTimestamp();
            if (orderTimestamp == null) {
                logger.warn("[EXPIRY] Hidden order {} has no timestamp - skipping expiry check.", order.getId());
                continue;
            }
            long orderAgeMs = now - orderTimestamp.getTime();
            if (orderTimestamp.getTime() < cutoffTime) {
                logger.info("[EXPIRY] Cancelling expired hidden/trigger order: id={}, instrument={}, type={}, age={}ms ({}s)",
                        order.getId(),
                        order.getInstrument() != null ? order.getInstrument().toString() : "unknown",
                        order.getType(),
                        orderAgeMs,
                        orderAgeMs / 1000);
                try {
                    getExchange().getTradeService().cancelOrder(
                            new DefaultCancelOrderByInstrumentAndIdParams(order.getInstrument(), order.getId()));
                    cancelledCount++;
                    logger.info("[EXPIRY] Successfully cancelled hidden/trigger order: {}", order.getId());
                } catch (Exception e) {
                    logger.error("[EXPIRY] Failed to cancel hidden/trigger order {}: {}", order.getId(), e.getMessage(), e);
                }
            } else {
                logger.debug("[EXPIRY] Hidden order {} is within expiry window (age={}ms, limit={}ms). Keeping.",
                        order.getId(), orderAgeMs, orderExpiryMs);
            }
        }

        logger.info("[EXPIRY] Expiry check complete. Cancelled {}/{} regular + {}/{} hidden orders.",
                cancelledCount, limitOrders.size(), cancelledCount, hiddenOrders.size());
        return cancelledCount;
    }

    /**
     * Ensure every open position has both a stop-loss and a take-profit protective order.
     *
     * For each open position:
     * - Checks existing hidden/trigger orders for a matching STOP_LOSS and TAKE_PROFIT
     * - If stop-loss is missing, places one at position entry price ± 0.1%
     * - If take-profit is missing, places one at the predicted profit limit price
     *
     * Direction logic:
     * - LONG position: stop-loss = BID below entry, take-profit = ASK above entry
     * - SHORT position: stop-loss = ASK above entry, take-profit = BID below entry
     *
     * @return number of protective orders placed
     */
    public int ensureProtectiveOrders() throws IOException {
        List<OpenPosition> openPositions = getPositions();
        if (openPositions.isEmpty()) {
            logger.info("[PROTECT] No open positions found. Nothing to protect.");
            return 0;
        }

        OpenOrders openOrders = getExchange().getTradeService().getOpenOrders();
        // getHiddenOrders() returns List<? extends Order> in XChange 5.x
        List<? extends Order> existingProtectiveOrders = openOrders.getHiddenOrders();

        logger.info("[PROTECT] Checking {} open position(s) for missing protective orders. Existing hidden orders: {}",
                openPositions.size(), existingProtectiveOrders.size());

        // Log all existing protective orders for visibility
        existingProtectiveOrders.forEach(o -> {
            String intention = (o instanceof StopOrder) ? String.valueOf(((StopOrder) o).getIntention()) : "N/A";
            String stopPrice = (o instanceof StopOrder) ? String.valueOf(((StopOrder) o).getStopPrice()) : "N/A";
            logger.info("[PROTECT] Existing hidden order: id={}, instrument={}, type={}, intention={}, stopPrice={}",
                    o.getId(),
                    o.getInstrument() != null ? o.getInstrument().getBase().getCurrencyCode() : "unknown",
                    o.getType(),
                    intention,
                    stopPrice);
        });

        int placedCount = 0;

        for (OpenPosition position : openPositions) {
            String instrumentKey = position.getInstrument().getBase().getCurrencyCode();
            Instrument instrument = position.getInstrument();
            boolean isLong = position.getType() == OpenPosition.Type.LONG;
            BigDecimal entryPrice = position.getPrice();
            BigDecimal positionSize = position.getSize();

            // Use tracked quantity if available, otherwise use position size
            BigDecimal trackedQuantity = positionQuantities.get(instrumentKey);
            BigDecimal orderSize = (trackedQuantity != null) ? trackedQuantity : positionSize;

            logger.info("[PROTECT] Checking position: instrument={}, type={}, entryPrice={}, size={}, orderSize={}",
                    instrumentKey, position.getType(), entryPrice, positionSize, orderSize);

            // Determine order types based on position direction
            // LONG: stop-loss = BID (sell), take-profit = ASK (sell)
            // SHORT: stop-loss = ASK (buy), take-profit = BID (buy)
            String stopLossOrderType = isLong ? "BID" : "ASK";
            String takeProfitOrderType = isLong ? "ASK" : "BID";

            // Check if stop-loss exists for this instrument (cast to StopOrder to access intention)
            boolean hasStopLoss = existingProtectiveOrders.stream()
                    .anyMatch(o -> o.getInstrument() != null
                            && o.getInstrument().getBase().getCurrencyCode().equals(instrumentKey)
                            && (o instanceof StopOrder)
                            && ((StopOrder) o).getIntention() == StopOrder.Intention.STOP_LOSS);

            // Check if take-profit exists for this instrument
            boolean hasTakeProfit = existingProtectiveOrders.stream()
                    .anyMatch(o -> o.getInstrument() != null
                            && o.getInstrument().getBase().getCurrencyCode().equals(instrumentKey)
                            && (o instanceof StopOrder)
                            && ((StopOrder) o).getIntention() == StopOrder.Intention.TAKE_PROFIT);

            logger.info("[PROTECT] {} - hasStopLoss={}, hasTakeProfit={}", instrumentKey, hasStopLoss, hasTakeProfit);

            // Place missing stop-loss
            if (!hasStopLoss) {
                logger.warn("[PROTECT] {} - MISSING stop-loss! Placing stop-loss order now.", instrumentKey);
                try {
                    // Stop-loss: 0.1% beyond entry price in the loss direction
                    BigDecimal stopLossPrice;
                    if (isLong) {
                        // LONG: stop-loss below entry price (sell if price drops)
                        stopLossPrice = entryPrice.subtract(entryPrice.multiply(BigDecimal.valueOf(0.1 / 100.0)));
                    } else {
                        // SHORT: stop-loss above entry price (buy if price rises)
                        stopLossPrice = entryPrice.plus().add(entryPrice.multiply(BigDecimal.valueOf(0.1 / 100.0)));
                    }
                    stopLossPrice = priceDecimalPrecision(instrument, stopLossPrice);

                    logger.info("[PROTECT] {} - Placing stop-loss: type={}, stopPrice={}, size={}",
                            instrumentKey, stopLossOrderType, stopLossPrice, orderSize);

                    String orderId = getExchange().getTradeService()
                            .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(stopLossOrderType), instrument)
                                    .intention(StopOrder.Intention.STOP_LOSS)
                                    .stopPrice(stopLossPrice)
                                    .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                                    .originalAmount(orderSize)
                                    .build());
                    placedCount++;
                    logger.info("[PROTECT] {} - Stop-loss placed successfully: orderId={}, stopPrice={}",
                            instrumentKey, orderId, stopLossPrice);
                } catch (Exception e) {
                    logger.error("[PROTECT] {} - Failed to place stop-loss: {}", instrumentKey, e.getMessage(), e);
                }
            } else {
                logger.info("[PROTECT] {} - Stop-loss already exists. No action needed.", instrumentKey);
            }

            // Place missing take-profit
            if (!hasTakeProfit) {
                logger.warn("[PROTECT] {} - MISSING take-profit! Placing take-profit order now.", instrumentKey);
                try {
                    // Take-profit: use predicted profit limit price from spot/futures analysis
                    BigDecimal takeProfitPrice;
                    try {
                        takeProfitPrice = getProfitLimitPrice(instrument);
                        logger.info("[PROTECT] {} - Using predicted profit limit price: {}", instrumentKey, takeProfitPrice);
                    } catch (Exception e) {
                        // Fallback: 0.1% beyond entry price in the profit direction
                        logger.warn("[PROTECT] {} - Could not get predicted profit price ({}), using fallback +/-0.1%", instrumentKey, e.getMessage());
                        if (isLong) {
                            takeProfitPrice = entryPrice.plus().add(entryPrice.multiply(BigDecimal.valueOf(0.1 / 100.0)));
                        } else {
                            takeProfitPrice = entryPrice.subtract(entryPrice.multiply(BigDecimal.valueOf(0.1 / 100.0)));
                        }
                    }
                    takeProfitPrice = priceDecimalPrecision(instrument, takeProfitPrice);

                    logger.info("[PROTECT] {} - Placing take-profit: type={}, stopPrice={}, size={}",
                            instrumentKey, takeProfitOrderType, takeProfitPrice, orderSize);

                    String orderId = getExchange().getTradeService()
                            .placeStopOrder(new StopOrder.Builder(Order.OrderType.valueOf(takeProfitOrderType), instrument)
                                    .intention(StopOrder.Intention.TAKE_PROFIT)
                                    .stopPrice(takeProfitPrice)
                                    .flag(KrakenFuturesOrderFlags.REDUCE_ONLY)
                                    .originalAmount(orderSize)
                                    .build());
                    placedCount++;
                    logger.info("[PROTECT] {} - Take-profit placed successfully: orderId={}, stopPrice={}",
                            instrumentKey, orderId, takeProfitPrice);
                } catch (Exception e) {
                    logger.error("[PROTECT] {} - Failed to place take-profit: {}", instrumentKey, e.getMessage(), e);
                }
            } else {
                logger.info("[PROTECT] {} - Take-profit already exists. No action needed.", instrumentKey);
            }
        }

        logger.info("[PROTECT] Protective orders check complete. Placed {} new protective order(s) for {} position(s).",
                placedCount, openPositions.size());
        return placedCount;
    }

}
