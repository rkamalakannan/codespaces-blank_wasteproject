package com.hope.xchangepractice.controller;

import com.hope.xchangepractice.model.AnalysisResult;
import com.hope.xchangepractice.model.CryptoBar;
import com.hope.xchangepractice.service.MarketDataService;
import com.hope.xchangepractice.service.TechnicalAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST API for the Crypto Sipper market-trend analyser.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *  GET /api/health                          → service health check
 *  GET /api/price/{symbol}                  → latest spot price
 *  GET /api/bars/{symbol}                   → raw OHLCV bars (last 100 h)
 *  GET /api/analyse/{symbol}                → full Ta4j analysis + signal
 * </pre>
 *
 * <p>Symbol format: use {@code BTC-USDT} in the URL (hyphens are URL-safe);
 * the controller converts them to {@code BTC/USDT} for XChange internally.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CryptoAnalysisController {

    private final MarketDataService        marketDataService;
    private final TechnicalAnalysisService technicalAnalysisService;

    // ── Health ───────────────────────────────────────────────────────────────

    /**
     * Simple liveness probe used by Railway and load-balancers.
     *
     * @return {@code {"status":"UP"}}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "Crypto Sipper"));
    }

    // ── Price ────────────────────────────────────────────────────────────────

    /**
     * Returns the latest spot price for a trading pair.
     *
     * <p>Example: {@code GET /api/price/BTC-USDT}
     *
     * @param symbol URL-encoded pair, e.g. {@code BTC-USDT}
     * @return {@code {"symbol":"BTC/USDT","price":...}}
     */
    @GetMapping("/price/{symbol}")
    public ResponseEntity<?> getPrice(@PathVariable String symbol) {
        String pair = toXChangePair(symbol);
        try {
            BigDecimal price = marketDataService.fetchCurrentPrice(pair);
            return ResponseEntity.ok(Map.of("symbol", pair, "price", price));
        } catch (IOException e) {
            log.error("Failed to fetch price for {}: {}", pair, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not fetch price: " + e.getMessage()));
        }
    }

    // ── Raw bars ─────────────────────────────────────────────────────────────

    /**
     * Returns the last 100 hourly OHLCV bars fetched from Binance via XChange.
     *
     * <p>Example: {@code GET /api/bars/ETH-USDT}
     *
     * @param symbol URL-encoded pair, e.g. {@code ETH-USDT}
     * @return list of {@link CryptoBar} objects
     */
    @GetMapping("/bars/{symbol}")
    public ResponseEntity<?> getBars(@PathVariable String symbol) {
        String pair = toXChangePair(symbol);
        try {
            List<CryptoBar> bars = marketDataService.fetchHourlyBars(pair);
            return ResponseEntity.ok(bars);
        } catch (IOException e) {
            log.error("Failed to fetch bars for {}: {}", pair, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Could not fetch bars: " + e.getMessage()));
        }
    }

    // ── Full analysis ────────────────────────────────────────────────────────

    /**
     * Fetches live OHLCV data from Binance and runs the full Ta4j analysis.
     *
     * <p>Returns all indicator values (RSI, MACD, EMA, Bollinger Bands) plus
     * a BUY / SELL / HOLD signal with a human-readable explanation.
     *
     * <p>Example: {@code GET /api/analyse/BTC-USDT}
     *
     * @param symbol URL-encoded pair, e.g. {@code BTC-USDT}
     * @return {@link AnalysisResult} with indicators and signal
     */
    @GetMapping("/analyse/{symbol}")
    public ResponseEntity<?> analyse(@PathVariable String symbol) {
        String pair = toXChangePair(symbol);
        try {
            List<CryptoBar> bars   = marketDataService.fetchHourlyBars(pair);
            AnalysisResult  result = technicalAnalysisService.analyse(bars);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            log.error("Analysis failed for {}: {}", pair, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    /**
     * Converts a URL-safe symbol (e.g. {@code BTC-USDT}) to the XChange
     * slash format (e.g. {@code BTC/USDT}).
     */
    private String toXChangePair(String urlSymbol) {
        return urlSymbol.replace("-", "/").toUpperCase();
    }
}
