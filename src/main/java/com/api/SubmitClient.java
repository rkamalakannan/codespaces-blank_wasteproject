package com.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

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


    public static void sendOrder() throws KeyManagementException, InvalidKeyException, MalformedURLException, NoSuchAlgorithmException, IOException {
             // send limit order
            String orderType = "mkt";
            String symbol = "pf_bchusd";
            String side = "buy";
            BigDecimal size = BigDecimal.ONE;
            Object result = methods.sendOrder(orderType, symbol, side, size);
            System.out.println("sendOrder (limit):\n" + result);

    }

}
