package com.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SubmitClient {

  public SubmitClient(FuturesApi methods) {
    this.methods = methods;
  }


  private static final String apiPath = "https://demo-futures.kraken.com/derivatives";
  private static final String apiPublicKey = "GQgye1pDbiUEQN6nvDliHScfzVaHz0TWiq6J7K87gUJb4qZoo8mYrEJF";
  private static final String apiPrivateKey = "M7jcR99c+y5eZXRlMWf+WRP0TIwKX0EC4ItpZ0xiIGDdXcNGlEV3O8KHmNVSh/Q6hxQ1yngCp/NpesubyjE0eAF/";
  private static final int timeout = 10;
  private static final boolean checkCertificate = true;

  static FuturesApi methods = new FuturesApi(apiPath, apiPublicKey, apiPrivateKey, timeout, checkCertificate);


  public static void buyOrder() throws KeyManagementException, InvalidKeyException, MalformedURLException, NoSuchAlgorithmException, IOException {
    // send limit order
    String orderType = "mkt";
    String symbol = "pf_bchusd";
    String side = "buy";
    BigDecimal size = BigDecimal.ONE;
    Object result = methods.sendOrder(orderType, symbol, side, size, null);
    System.out.println("buyOrder (limit):\n" + result);

  }

  public static void sellOrder() throws KeyManagementException, InvalidKeyException, MalformedURLException, NoSuchAlgorithmException, IOException {
    // send limit order
    String orderType = "mkt";
    String symbol = "pf_bchusd";
    String side = "sell";
    BigDecimal size = BigDecimal.ONE;
    Object result = methods.sendOrder(orderType, symbol, side, size, null);
    System.out.println("buyOrder (limit):\n" + result);

  }

  /**
 * @throws KeyManagementException
 * @throws InvalidKeyException
 * @throws MalformedURLException
 * @throws NoSuchAlgorithmException
 * @throws IOException
 */
public static BigDecimal findTicker(String symbol) throws KeyManagementException, InvalidKeyException, MalformedURLException, NoSuchAlgorithmException, IOException {
    ObjectMapper mapper = new ObjectMapper();
    String json = methods.getTickers();
    Result readValue = mapper.readValue(json, Result.class);

    BigDecimal lastPricePerpetual = BigDecimal.valueOf(readValue.getTickers().stream().filter(it -> it.getSymbol().equals(symbol)).findFirst().get().getLast());

    return lastPricePerpetual;
  }

  public static void buyLimitOrder(String symbol, BigDecimal limitPrice) throws KeyManagementException, InvalidKeyException, MalformedURLException, NoSuchAlgorithmException, IOException {
    // send limit order
    String side = "buy";
    String orderType = "lmt";
    BigDecimal size = BigDecimal.valueOf(100);
//    limitPrice = limitPrice.setScale(0, RoundingMode.DOWN);
    int leverage = 15;
//    Object leverageResult = methods.sendMaxLeverage(leverage, symbol);
//    System.out.println("Levaerage sell  (limit):\n" + leverageResult);

    Object result = methods.sendOrder(orderType, symbol, side, size, limitPrice);
    System.out.println("buyOrder (limit):\n" + result);

  }

  public static void sellLimitOrder(String symbol, BigDecimal limitPrice) throws KeyManagementException, InvalidKeyException, MalformedURLException, NoSuchAlgorithmException, IOException {
    // send limit order
    String side = "sell";
    String orderType = "lmt";
    BigDecimal size = BigDecimal.valueOf(100);
//    limitPrice = limitPrice.setScale(0, RoundingMode.DOWN);

    int leverage = 15;
    // leverage =  leverage.stripTrailingZeros();
    // leverage = leverage.setScale(0, RoundingMode.DOWN);
//    Object leverageResult = methods.sendMaxLeverage(leverage, symbol);
//    System.out.println("Levaerage sell  (limit):\n" + leverageResult);


    Object result = methods.sendOrder(orderType, symbol, side, size, limitPrice);
    System.out.println("buyOrder (limit):\n" + result);

  }


}
