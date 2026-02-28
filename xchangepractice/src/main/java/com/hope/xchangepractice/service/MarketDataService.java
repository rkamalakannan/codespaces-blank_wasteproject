package com.hope.xchangepractice.service;

import com.hope.xchangepractice.model.CryptoBar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches live OHLCV candlestick data directly from Binance REST API.
 * 
 * This service uses direct HTTP calls to Binance instead of XChange library
 * to avoid API compatibility issues.
 */
@Slf4j
@Service
public class MarketDataService {

    private static final String BINANCE_API_BASE = "https://api.binance.com";
    private static final int BAR_LIMIT = 100;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Fetches the most recent {@value #BAR_LIMIT} hourly OHLCV bars for the
     * given trading pair from Binance.
     *
     * @param symbol trading pair in format, e.g. {@code "BTCUSDT"} (no slash)
     * @return ordered list of {@link CryptoBar} objects (oldest → newest)
     * @throws IOException if the Binance REST call fails
     */
    public List<CryptoBar> fetchHourlyBars(String symbol) throws IOException {
        log.info("Fetching {} hourly bars for {} from Binance", BAR_LIMIT, symbol);
        
        // Convert symbol format (BTC/USDT -> BTCUSDT)
        String pair = symbol.replace("/", "");
        
        // Build Binance klines API URL
        String url = String.format("%s/api/v3/klines?symbol=%s&interval=1h&limit=%d", 
                                   BINANCE_API_BASE, pair, BAR_LIMIT);
        
        // Fetch data from Binance
        String response = fetchFromBinance(url);
        
        // Parse JSON response
        JsonNode klines = objectMapper.readTree(response);
        
        List<CryptoBar> bars = new ArrayList<>();
        for (JsonNode kline : klines) {
            CryptoBar bar = CryptoBar.builder()
                    .symbol(symbol)
                    .openTime(Instant.ofEpochMilli(kline.get(0).asLong()).atZone(ZoneOffset.UTC))
                    .open(new BigDecimal(kline.get(1).asText()))
                    .high(new BigDecimal(kline.get(2).asText()))
                    .low(new BigDecimal(kline.get(3).asText()))
                    .close(new BigDecimal(kline.get(4).asText()))
                    .volume(new BigDecimal(kline.get(5).asText()))
                    .build();
            bars.add(bar);
        }
        
        log.info("Successfully fetched {} bars for {}", bars.size(), symbol);
        return bars;
    }

    /**
     * Returns the latest spot price for a trading pair.
     *
     * @param symbol e.g. {@code "BTC/USDT"}
     * @return current price
     * @throws IOException if the Binance REST call fails
     */
    public BigDecimal fetchCurrentPrice(String symbol) throws IOException {
        log.info("Fetching current price for {} from Binance", symbol);
        
        // Convert symbol format (BTC/USDT -> BTCUSDT)
        String pair = symbol.replace("/", "");
        
        // Build Binance ticker API URL
        String url = String.format("%s/api/v3/ticker/price?symbol=%s", BINANCE_API_BASE, pair);
        
        // Fetch data from Binance
        String response = fetchFromBinance(url);
        
        // Parse JSON response
        JsonNode ticker = objectMapper.readTree(response);
        return new BigDecimal(ticker.get("price").asText());
    }
    
    /**
     * Helper method to fetch data from Binance API
     */
    private String fetchFromBinance(String url) throws IOException {
        try {
            java.net.URI uri = new java.net.URI(url);
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Accept", "application/json")
                    .build();
            
            java.net.http.HttpResponse<String> response = client.send(request, 
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new IOException("Binance API error: HTTP " + response.statusCode());
            }
            
            return response.body();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid URL: " + url, e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("Request timeout", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
}
