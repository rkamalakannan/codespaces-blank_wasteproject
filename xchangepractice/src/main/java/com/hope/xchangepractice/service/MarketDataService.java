package com.hope.xchangepractice.service;

import com.hope.xchangepractice.model.CryptoBar;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.binance.BinanceExchange;
import org.knowm.xchange.binance.BinanceAdapters;
import org.knowm.xchange.binance.dto.marketdata.KlineInterval;
import org.knowm.xchange.binance.service.BinanceMarketDataServiceRaw;
import org.knowm.xchange.currency.CurrencyPair;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fetches live OHLCV candlestick data from Binance via the XChange library.
 *
 * <h2>Why XChange?</h2>
 * XChange provides a single, unified Java API for 60+ crypto exchanges.
 * Switching from Binance to another exchange only requires changing the
 * {@code Exchange} implementation class — all downstream code stays the same.
 *
 * <h2>Why Binance?</h2>
 * Binance offers a public, unauthenticated REST endpoint for kline (OHLCV)
 * data, so no API key is required for read-only market analysis.
 *
 * <h2>XChange API used</h2>
 * We use {@link BinanceMarketDataServiceRaw#getBinanceKlines} which maps
 * directly to the Binance {@code GET /api/v3/klines} endpoint.
 */
@Slf4j
@Service
public class MarketDataService {

    /**
     * Number of historical bars to fetch per request.
     * Ta4j needs at least 26 bars for MACD and 20 for Bollinger Bands.
     * We fetch 100 to give all indicators enough warm-up data.
     */
    private static final int BAR_LIMIT = 100;

    /**
     * Fetches the most recent {@value #BAR_LIMIT} hourly OHLCV bars for the
     * given trading pair from Binance.
     *
     * <p>XChange flow:
     * <ol>
     *   <li>Create a {@link BinanceExchange} instance (no credentials needed)</li>
     *   <li>Cast its {@code MarketDataService} to {@link BinanceMarketDataServiceRaw}</li>
     *   <li>Call {@code getBinanceKlines(pair, interval, limit, startTime, endTime)}</li>
     *   <li>Map each kline to our {@link CryptoBar} model</li>
     * </ol>
     *
     * @param symbol trading pair in XChange format, e.g. {@code "BTC/USDT"}
     * @return ordered list of {@link CryptoBar} objects (oldest → newest)
     * @throws IOException if the Binance REST call fails
     */
    public List<CryptoBar> fetchHourlyBars(String symbol) throws IOException {
        log.info("Fetching {} hourly bars for {} from Binance via XChange", BAR_LIMIT, symbol);

        // XChange: create a Binance exchange instance (public API, no key needed)
        Exchange binance = ExchangeFactory.INSTANCE.createExchange(BinanceExchange.class);

        // Cast to the Binance-specific raw service to access kline (OHLCV) data
        BinanceMarketDataServiceRaw rawService =
                (BinanceMarketDataServiceRaw) binance.getMarketDataService();

        CurrencyPair pair = new CurrencyPair(symbol);

        // Fetch klines: h1 interval, last BAR_LIMIT candles, no time filter
        var klines = rawService.getBinanceKlines(pair, KlineInterval.h1, BAR_LIMIT, null, null);

        return klines.stream()
                .map(k -> CryptoBar.builder()
                        .symbol(symbol)
                        .openTime(Instant.ofEpochMilli(k.openTime).atZone(ZoneOffset.UTC))
                        .open(k.openPrice)
                        .high(k.highPrice)
                        .low(k.lowPrice)
                        .close(k.closePrice)
                        .volume(k.volume)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Returns the latest spot price for a trading pair.
     *
     * <p>Uses the standard XChange {@code MarketDataService.getTicker()} which
     * calls Binance {@code GET /api/v3/ticker/bookTicker} under the hood.
     *
     * @param symbol e.g. {@code "BTC/USDT"}
     * @return current price
     * @throws IOException if the Binance REST call fails
     */
    public BigDecimal fetchCurrentPrice(String symbol) throws IOException {
        log.info("Fetching current price for {} from Binance via XChange", symbol);

        Exchange binance = ExchangeFactory.INSTANCE.createExchange(BinanceExchange.class);
        CurrencyPair pair = new CurrencyPair(symbol);

        // Standard XChange interface — works the same across all exchanges
        var ticker = binance.getMarketDataService().getTicker(pair);
        return ticker.getLast();
    }
}
