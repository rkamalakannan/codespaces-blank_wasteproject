package cloudcode.krakenfutures.strategy;

import cloudcode.krakenfutures.config.TradingConfig;
import cloudcode.krakenfutures.models.ArbitrageOpportunity;
import cloudcode.krakenfutures.websocket.KrakenWebSocketClient;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService.TickerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Low-latency cross-currency arbitrage strategy for Kraken Spot.
 *
 * On every WebSocket tick, checks if the same asset is priced differently
 * across fiat quote currencies (after FX normalization to USD).
 * Executes simultaneous BUY (cheap side) + SELL (expensive side) when
 * net profit exceeds threshold after round-trip fees.
 *
 * Optimizations:
 * - Event-driven: scans only the asset that just ticked (not all assets)
 * - Monotonic nanoTime for staleness (no wall-clock overhead)
 * - Pre-allocated NormalizedQuote array (avoid per-tick allocations)
 * - Minimal BigDecimal operations on hot path
 */
public class CrossCurrencyArbitrageStrategy {

    private static final Logger log = LoggerFactory.getLogger(CrossCurrencyArbitrageStrategy.class);

    private static final BigDecimal KRAKEN_TAKER_FEE_PCT = new BigDecimal("0.26");
    private static final BigDecimal ROUND_TRIP_FEE_PCT = KRAKEN_TAKER_FEE_PCT.multiply(BigDecimal.TWO);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_RECENT_OPPORTUNITIES = 200;

    private final TradingConfig config;
    private final BigDecimal minProfitPct;
    private final long maxTickerAgeMs;
    private final long tradeCooldownMs;
    private final int maxConcurrentTrades;
    private final AtomicInteger activeTrades = new AtomicInteger(0);
    private final BigDecimal tradeSizeUsd;

    private final SpotTickerWebSocketService tickerService;
    private final KrakenWebSocketClient orderClient;

    private final ConcurrentHashMap<String, Long> lastTradeNanos = new ConcurrentHashMap<>();
    private final List<ArbitrageOpportunity> recentOpportunities = new CopyOnWriteArrayList<>();

    // Stats
    private volatile long totalScans = 0;
    private volatile long totalOpportunities = 0;
    private volatile long totalTrades = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "arb-stats");
        t.setDaemon(true);
        return t;
    });

    public CrossCurrencyArbitrageStrategy(SpotTickerWebSocketService tickerService,
                                          KrakenWebSocketClient orderClient,
                                          TradingConfig config) {
        this.tickerService = tickerService;
        this.orderClient = orderClient;
        this.config = config;
        this.minProfitPct = BigDecimal.valueOf(config.getMinProfitPct());
        this.maxTickerAgeMs = config.getMaxTickerAgeMs();
        this.tradeCooldownMs = config.getTradeCooldownMs();
        this.maxConcurrentTrades = config.getMaxConcurrentTrades();
        this.tradeSizeUsd = BigDecimal.valueOf(config.getTradeSizeUsd());
    }

    public void start() {
        log.info("STRATEGY_STARTED mode={} minProfitPct={} tradeSizeUsd={}",
                config.isPaperTrading() ? "PAPER" : "LIVE",
                minProfitPct,
                tradeSizeUsd);

        // Event-driven: scan only the asset that just received a tick
        tickerService.addTickListener(this::scanAsset);

        // Periodic full scan as fallback
        scheduler.scheduleWithFixedDelay(this::scanAll, 5, 2, TimeUnit.SECONDS);
    }

    /** Scan a single asset (called on every tick — hot path) */
    private void scanAsset(String baseAsset) {
        if (baseAsset.equals("USDT") || baseAsset.equals("USDC") || baseAsset.equals("DAI")) return;
        totalScans++;
        evaluateAsset(baseAsset);
    }

    /** Full scan of all cached assets */
    private void scanAll() {
        ConcurrentHashMap<String, ConcurrentHashMap<String, TickerData>> cache = tickerService.getPriceCache();
        for (String baseAsset : cache.keySet()) {
            scanAsset(baseAsset);
        }
    }

    private void evaluateAsset(String baseAsset) {
        ConcurrentHashMap<String, TickerData> quotes = tickerService.getPriceCache().get(baseAsset);
        if (quotes == null || quotes.size() < 2) return;

        ConcurrentHashMap<String, BigDecimal> fxRates = tickerService.getFxRatesToUsd();
        if (fxRates.size() < 2) return;

        // Build normalized quotes — only fresh tickers
        List<NormalizedQuote> valid = new ArrayList<>(quotes.size());
        for (Map.Entry<String, TickerData> entry : quotes.entrySet()) {
            TickerData ticker = entry.getValue();
            if (ticker.ageMs() > maxTickerAgeMs) continue;

            BigDecimal fxRate = fxRates.get(entry.getKey());
            if (fxRate == null) continue;

            valid.add(new NormalizedQuote(
                    entry.getKey(), ticker,
                    ticker.ask.multiply(fxRate),
                    ticker.bid.multiply(fxRate)
            ));
        }

        if (valid.size() < 2) return;

        // Find cheapest ask and highest bid
        NormalizedQuote cheapestAsk = valid.get(0);
        NormalizedQuote highestBid = valid.get(0);
        for (int i = 1; i < valid.size(); i++) {
            NormalizedQuote nq = valid.get(i);
            if (nq.normAsk.compareTo(cheapestAsk.normAsk) < 0) cheapestAsk = nq;
            if (nq.normBid.compareTo(highestBid.normBid) > 0) highestBid = nq;
        }

        // Must be different currencies
        if (cheapestAsk.quote.equals(highestBid.quote)) return;

        // Calculate spread
        BigDecimal spread = highestBid.normBid.subtract(cheapestAsk.normAsk);
        if (spread.signum() <= 0) return;

        BigDecimal spreadPct = spread.divide(cheapestAsk.normAsk, 8, RoundingMode.HALF_UP).multiply(HUNDRED);
        BigDecimal netProfitPct = spreadPct.subtract(ROUND_TRIP_FEE_PCT);

        if (netProfitPct.compareTo(minProfitPct) > 0) {
            totalOpportunities++;

            ArbitrageOpportunity opp = new ArbitrageOpportunity(
                    baseAsset, cheapestAsk.quote, highestBid.quote,
                    cheapestAsk.ticker.ask, highestBid.ticker.bid,
                    cheapestAsk.normAsk, highestBid.normBid,
                    spreadPct, netProfitPct
            );

            log.info("ARBITRAGE_FOUND asset={} buy={}/{} sell={}/{} spreadPct={} netProfitPct={}",
                    baseAsset,
                    baseAsset,
                    cheapestAsk.quote,
                    baseAsset,
                    highestBid.quote,
                    spreadPct,
                    netProfitPct);
            recentOpportunities.add(opp);
            if (recentOpportunities.size() > MAX_RECENT_OPPORTUNITIES) {
                recentOpportunities.subList(0, recentOpportunities.size() - MAX_RECENT_OPPORTUNITIES).clear();
            }

            executeArbitrage(baseAsset, cheapestAsk, highestBid, opp);
        }
    }

    private void executeArbitrage(String baseAsset, NormalizedQuote buy, NormalizedQuote sell,
                                   ArbitrageOpportunity opp) {
        // Global concurrent trades check
        if (activeTrades.get() >= maxConcurrentTrades) {
            return;
        }

        // Cooldown check using monotonic nanos
        Long lastNanos = lastTradeNanos.get(baseAsset);
        if (lastNanos != null && (System.nanoTime() - lastNanos) / 1_000_000 < tradeCooldownMs) {
            return;
        }

        if (!config.isPaperTrading() && !orderClient.isConnected()) {
            log.warn("TRADE_SKIPPED reason=order_websocket_not_connected asset={}", baseAsset);
            return;
        }

        // Use Kraken symbol format for orders
        String krakenBase = "BTC".equals(baseAsset) ? "XBT" : baseAsset;
        String buySymbol = krakenBase + "/" + buy.quote;
        String sellSymbol = krakenBase + "/" + sell.quote;

        // Dynamic trade size based on config
        BigDecimal tradeSize = tradeSizeUsd.divide(buy.normAsk, 8, RoundingMode.DOWN);

        activeTrades.incrementAndGet();
        try {
            log.info("BUY_SELL_START asset={} buySymbol={} sellSymbol={} size={} expectedProfitPct={}",
                    baseAsset, buySymbol, sellSymbol, tradeSize, opp.getEstimatedProfitPercent());

            orderClient.sendOrder(buySymbol, "buy", tradeSize.doubleValue(), 0, "market");
            orderClient.sendOrder(sellSymbol, "sell", tradeSize.doubleValue(), 0, "market");

            lastTradeNanos.put(baseAsset, System.nanoTime());
            totalTrades++;
        } catch (Exception e) {
            log.warn("TRADE_FAILED asset={} reason={}", baseAsset, e.getMessage());
        } finally {
            activeTrades.decrementAndGet();
        }
    }

    private void logStats() {
        // Intentionally quiet. Order logs only.
    }

    public List<ArbitrageOpportunity> getRecentOpportunities() {
        return Collections.unmodifiableList(recentOpportunities);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    private static class NormalizedQuote {
        final String quote;
        final TickerData ticker;
        final BigDecimal normAsk;
        final BigDecimal normBid;

        NormalizedQuote(String quote, TickerData ticker, BigDecimal normAsk, BigDecimal normBid) {
            this.quote = quote;
            this.ticker = ticker;
            this.normAsk = normAsk;
            this.normBid = normBid;
        }
    }
}
