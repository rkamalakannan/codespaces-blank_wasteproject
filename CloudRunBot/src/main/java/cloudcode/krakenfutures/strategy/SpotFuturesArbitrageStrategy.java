package cloudcode.krakenfutures.strategy;

import cloudcode.krakenfutures.config.TradingConfig;
import cloudcode.krakenfutures.rest.KrakenRestClient;
import cloudcode.krakenfutures.websocket.FuturesTickerService;
import cloudcode.krakenfutures.websocket.FuturesTickerService.FuturesTickerData;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService.TickerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spot-Futures Basis Trading Strategy.
 * 
 * Monitors the price difference (basis) between Kraken Spot and Kraken Futures.
 * When the futures price significantly deviates from the spot price (normalized to USD),
 * it triggers a trade in the futures market.
 * 
 * Typically:
 * - If Futures > Spot + Threshold: Short Futures (expecting basis to narrow)
 * - If Futures < Spot - Threshold: Long Futures (expecting basis to narrow)
 */
public class SpotFuturesArbitrageStrategy {

    private static final Logger log = LoggerFactory.getLogger(SpotFuturesArbitrageStrategy.class);

    private final SpotTickerWebSocketService spotTickerService;
    private final FuturesTickerService futuresTickerService;
    private final KrakenRestClient restClient;
    private final TradingConfig config;

    private final BigDecimal minProfitPct;
    private final long maxTickerAgeMs;
    private final long tradeCooldownMs;
    private final AtomicInteger activeTrades = new AtomicInteger(0);

    private final ConcurrentHashMap<String, Long> lastTradeNanos = new ConcurrentHashMap<>();

    public SpotFuturesArbitrageStrategy(SpotTickerWebSocketService spotTickerService,
                                        FuturesTickerService futuresTickerService,
                                        KrakenRestClient restClient,
                                        TradingConfig config) {
        this.spotTickerService = spotTickerService;
        this.futuresTickerService = futuresTickerService;
        this.restClient = restClient;
        this.config = config;
        this.minProfitPct = BigDecimal.valueOf(config.getMinProfitPct());
        this.maxTickerAgeMs = config.getMaxTickerAgeMs();
        this.tradeCooldownMs = config.getTradeCooldownMs();
        log.info("SPOT_FUTURES_STRATEGY_WIRED restClient={}", restClient != null);
    }

    public void start() {
        log.info("SPOT_FUTURES_STRATEGY_STARTED minProfitPct={}", minProfitPct);

        // Listen to futures ticks
        futuresTickerService.addTickListener(this::onFuturesTick);

        // Also listen to spot ticks to trigger evaluation
        spotTickerService.addTickListener(this::onSpotTick);
    }

    private void onFuturesTick(String productId) {
        evaluate(productId);
    }

    private void onSpotTick(String baseAsset) {
        // Find futures products that match this base asset
        // Simplified: Kraken Perpetual Futures often have names like PF_XBTUSD
        // We'll try to find a match.
        String futuresProductId = mapSpotToBaseFutures(baseAsset);
        if (futuresProductId != null) {
            evaluate(futuresProductId);
        }
    }

    private String mapSpotToBaseFutures(String baseAsset) {
        // Common mappings - support both BTC and XBT
        if ("BTC".equals(baseAsset) || "XBT".equals(baseAsset)) return "PF_XBTUSD";
        if ("ETH".equals(baseAsset)) return "PF_ETHUSD";
        if ("LTC".equals(baseAsset)) return "PF_LTCUSD";
        if ("BCH".equals(baseAsset)) return "PF_BCHUSD";
        if ("XRP".equals(baseAsset)) return "PF_XRPUSD";
        if ("DOT".equals(baseAsset)) return "PF_DOTUSD";
        if ("ADA".equals(baseAsset)) return "PF_ADAUSD";
        if ("SOL".equals(baseAsset)) return "PF_SOLUSD";
        if ("LINK".equals(baseAsset)) return "PF_LINKUSD";
        if ("AVAX".equals(baseAsset)) return "PF_AVAXUSD";
        return null;
    }

    private String mapFuturesToSpotBase(String productId) {
        if (productId.startsWith("PF_")) {
            String base = productId.substring(3).replace("USD", "");
            // Convert XBT to BTC for spot ticker lookup
            if ("XBT".equals(base)) {
                return "BTC";
            }
            return base;
        }
        return null;
    }

    private void evaluate(String productId) {
        FuturesTickerData futuresTicker = futuresTickerService.getTicker(productId);
        if (futuresTicker == null || futuresTicker.ageMs() > maxTickerAgeMs) return;

        String baseAsset = mapFuturesToSpotBase(productId);
        if (baseAsset == null) return;

        TickerData spotTicker = spotTickerService.getTicker(baseAsset, "USD");
        if (spotTicker == null || spotTicker.ageMs() > maxTickerAgeMs) {
            // Try USDT if USD is not available
            spotTicker = spotTickerService.getTicker(baseAsset, "USDT");
        }

        if (spotTicker == null || spotTicker.ageMs() > maxTickerAgeMs) return;

        BigDecimal spotPrice = spotTicker.last;
        BigDecimal futuresPrice = futuresTicker.last;

        if (spotPrice == null || futuresPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) return;

        // Basis = (Futures - Spot) / Spot
        BigDecimal diff = futuresPrice.subtract(spotPrice);
        BigDecimal basisPct = diff.multiply(BigDecimal.valueOf(100))
                                  .divide(spotPrice, 4, RoundingMode.HALF_UP);

        log.debug("Basis for {}: {}% (Spot: {}, Futures: {})", productId, basisPct, spotPrice, futuresPrice);

        if (basisPct.abs().compareTo(minProfitPct) >= 0) {
            executeTrade(productId, basisPct, spotPrice, futuresPrice);
        }
    }

    private void executeTrade(String productId, BigDecimal basisPct, BigDecimal spotPrice, BigDecimal futuresPrice) {
        long now = System.nanoTime();
        Long lastTrade = lastTradeNanos.get(productId);
        if (lastTrade != null && (now - lastTrade) < TimeUnit.MILLISECONDS.toNanos(tradeCooldownMs)) {
            return;
        }

        if (activeTrades.get() >= config.getMaxConcurrentTrades()) {
            return;
        }

        lastTradeNanos.put(productId, now);
        activeTrades.incrementAndGet();

        String side = basisPct.compareTo(BigDecimal.ZERO) > 0 ? "sell" : "buy";
        double size = config.getTradeSizeUsd() / futuresPrice.doubleValue();

        // Immediate Stop Loss and Take Profit
        // Profit price is usually the spot price (since we expect basis to narrow to spot)
        Double stopLossPrice = null;
        Double takeProfitPrice = spotPrice.doubleValue();

        // Calculate a 2% stop loss for safety (can be made configurable)
        if ("buy".equals(side)) {
            stopLossPrice = futuresPrice.multiply(new BigDecimal("0.98")).doubleValue();
        } else {
            stopLossPrice = futuresPrice.multiply(new BigDecimal("1.02")).doubleValue();
        }

        log.info("BASIS_TRADE_START product={} side={} basisPct={} spot={} futures={} size={} sl={} tp={}",
                productId, side, basisPct, spotPrice, futuresPrice, size, stopLossPrice, takeProfitPrice);

        if (config.isPaperTrading()) {
            log.info("BASIS_ORDER_PATH mode=PAPER client=none restClient={} side={} product={}", restClient != null, side, productId);
            log.info("BASIS_ORDER_OK mode=PAPER side={} size={} product={} sl={} tp={}", side, size, productId, stopLossPrice, takeProfitPrice);
            activeTrades.decrementAndGet();
        } else {
            log.info("BASIS_ORDER_PATH mode=LIVE client=restClient restClient={} side={} product={}", restClient != null, side, productId);
            final Double sl = stopLossPrice;
            final Double tp = takeProfitPrice;
            CompletableFuture.runAsync(() -> {
                try {
                    boolean success = restClient.placeFuturesOrder(productId, side, size, null, "mkt", sl, tp);
                    if (success) {
                        log.info("BASIS_ORDER_OK side={} size={} product={} sl={} tp={}", side, size, productId, sl, tp);
                    } else {
                        log.warn("BASIS_ORDER_FAILED product={} side={} size={}", productId, side, size);
                    }
                } catch (Exception e) {
                    log.warn("BASIS_ORDER_ERROR product={} reason={}", productId, e.getMessage());
                } finally {
                    activeTrades.decrementAndGet();
                }
            });
        }
    }

    public void shutdown() {
        log.info("Shutting down Spot-Futures Arbitrage Strategy");
    }
}
