package com.hope.xchangepractice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Crypto Sipper — entry point.
 *
 * <p>Starts the Spring Boot application which exposes a REST API for
 * real-time crypto market analysis powered by:
 * <ul>
 *   <li><b>XChange</b> — fetches live OHLCV data from Binance</li>
 *   <li><b>Ta4j</b>    — computes RSI, MACD, EMA, Bollinger Bands and
 *                         generates BUY / SELL / HOLD signals</li>
 * </ul>
 */
@SpringBootApplication
public class XchangepracticeApplication {

    public static void main(String[] args) {
        SpringApplication.run(XchangepracticeApplication.class, args);
    }
}
