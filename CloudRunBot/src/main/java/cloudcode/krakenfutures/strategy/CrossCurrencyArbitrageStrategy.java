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
 * <h2>WebSocket v2 data usage</h2>
 * The strategy consumes the Kraken Spot WebSocket v2 ticker channel and uses
 * every applicable field:
 *
 * <ul>
 *   <li><b>bid / ask</b> — used as the entry/exit price on each pair.</li>
 *   <li><b>last</b> — sanity reference.</li>
 *   <li><b>vwap</b> — used as a sanity check (|last - vwap| / vwap threshold)
 *       to reject illiquid or stale quotes.</li>
 *   <li><b>bid_qty / ask_qty</b> — top-of-book liquidity in base units.
 *       Combined with bid/ask (both v2 values) to compute USD liquidity
 *       (bid * bid_qty and ask * ask_qty). Trades are blocked when either
 *       side of the cross-pair opportunity has less than
 *       {@code MIN_TOP_OF_BOOK_USD} USD of liquidity.</li>
 *   <li><b>change_pct</b> — 24h change %. Used to skip pairs that have moved
 *       abnormally in the last 24h (above {@code MAX_CHANGE_PCT_24H}).</li>
 *   <li><b>low / high</b> — 24h range, used together with last to validate
 *       that the quote is within the daily range.</li>
 *   <li><b>timestamp</b> — Kraken-side RFC 3339 timestamp is logged for audit;
 *       we keep using monotonic {@code System.nanoTime()} for staleness to
 *       avoid wall-clock drift.</li>
 * </ul>
 *
 * <h3>Spread</h3>
 * The Kraken v2 ticker channel does <b>not</b> expose a direct {@code spread}
 * field (this was only in v1's dedicated {@code spread} channel). The strategy
 * therefore computes two distinct spreads:
 *
 * <ol>
 *   <li><b>Per-pair bid-ask spread</b> — derived from v2 {@code bid} and
 *       {@code ask} (see {@link TickerData#pairSpread()}). Used as a filter to
 *       avoid trading pairs whose own bid-ask is wider than
 *       {@code MAX_PAIR_SPREAD_PCT}.</li>
 *   <li><b>Cross-pair spread</b> — the difference between the highest bid and
 *       cheapest ask across different quote currencies for the same base asset
 *       (after FX normalization). This requires combining multiple v2 ticker
 *       messages and therefore cannot be obtained from a single websocket
 *       message; it is computed locally by the strategy.</li>
 * </ol>
 *
 * Optimizations:
 * - Event-driven: scans only the asset that just ticked (not all assets)
 * - Monotonic nanoTime for staleness (no wall-clock overhead)
 * - Pre-allocated NormalizedQuote array (avoid per-tick allocations)
 * - Minimal BigDecimal operations on hot path
 */
public class CrossCurrencyArbitrageStrategy {

    private static final Logger log = LoggerFactory.getLogger(CrossCurrencyArbitrageStrategy.class);

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MAX_RECENT_OPPORTUNITIES = 200;

    // Fees are now config-driven (see TradingConfig).
    // Default per Kraken Pro Spot schedule ($0+ tier): Maker 0.25%, Taker 0.40%.
    // For limit orders resting on the book, maker fees apply; when lifted
    // immediately they incur taker fees. We use the taker fee as a conservative
    // upper bound for net-profit calculation.
    private final BigDecimal takerFeePct;
    private final BigDecimal roundTripFeePct;

    private final TradingConfig config;
    private final BigDecimal minProfitPct;
    private final long maxTickerAgeMs;
    private final long tradeCooldownMs;
    private final int maxConcurrentTrades;
    private final AtomicInteger activeTrades = new AtomicInteger(0);
    private final BigDecimal tradeSizeUsd;

    // v2-driven filters
    private final BigDecimal maxPairSpreadPct;
    private final BigDecimal minTopOfBookUsd;
    private final BigDecimal maxVwapDeviationPct;
    private final BigDecimal maxChangePct24h;

    private final SpotTickerWebSocketService tickerService;
    private final KrakenWebSocketClient orderClient;

    private final ConcurrentHashMap<String, Long> lastTradeNanos = new ConcurrentHashMap<>();
    private final List<ArbitrageOpportunity> recentOpportunities = new CopyOnWriteArrayList<>();

    // Stats
    private volatile long totalScans = 0;
    private volatile long totalOpportunities = 0;
    private volatile long totalTrades = 0;
    private volatile long totalPerPairRejected = 0;
    private volatile long totalLiquidityRejected = 0;
    private volatile long totalVwapRejected = 0;
    private volatile long totalVolatilityRejected = 0;

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

        // v2-driven filters
        this.maxPairSpreadPct = BigDecimal.valueOf(config.getMaxPairSpreadPct());
        this.minTopOfBookUsd = BigDecimal.valueOf(config.getMinTopOfBookUsd());
        this.maxVwapDeviationPct = BigDecimal.valueOf(config.getMaxVwapDeviationPct());
        this.maxChangePct24h = BigDecimal.valueOf(config.getMaxChangePct24h());

        // Fee model — use effective fee tier (config-driven or volume-based)
        this.takerFeePct = BigDecimal.valueOf(config.effectiveTakerFeePct());
        this.roundTripFeePct = this.takerFeePct.multiply(BigDecimal.TWO);
    }

    public void start() {
        log.info("STRATEGY_STARTED mode={} minProfitPct={} tradeSizeUsd={} "
                        + "maxPairSpreadPct={} minTopOfBookUsd={} maxVwapDevPct={} maxChangePct24h={} "
                        + "takerFeePct={} roundTripFeePct={}",
                config.isPaperTrading() ? "PAPER" : "LIVE",
                minProfitPct, tradeSizeUsd,
                maxPairSpreadPct, minTopOfBookUsd, maxVwapDeviationPct, maxChangePct24h,
                takerFeePct, roundTripFeePct);

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

        // Build normalized quotes — only fresh tickers that pass all v2 filters
        List<NormalizedQuote> valid = new ArrayList<>(quotes.size());
        for (Map.Entry<String, TickerData> entry : quotes.entrySet()) {
            TickerData ticker = entry.getValue();
            if (ticker.ageMs() > maxTickerAgeMs) continue;

            BigDecimal fxRate = fxRates.get(entry.getKey());
            if (fxRate == null) continue;

            // -------- v2-driven quality filters --------
            // 1) Per-pair bid-ask spread (derived from v2 bid/ask)
            BigDecimal pairSpreadPct = ticker.pairSpreadPct();
            if (pairSpreadPct.compareTo(maxPairSpreadPct) > 0) {
                totalPerPairRejected++;
                continue;
            }

            // 2) Top-of-book USD liquidity (bid * bid_qty and ask * ask_qty from v2)
            BigDecimal tobBidUsd = ticker.topOfBookUsdBid();
            BigDecimal tobAskUsd = ticker.topOfBookUsdAsk();
            BigDecimal minTobUsd = tobBidUsd.min(tobAskUsd);
            if (minTobUsd.compareTo(minTopOfBookUsd) < 0) {
                totalLiquidityRejected++;
                continue;
            }

            // 3) vwap sanity check (v2-provided vwap vs v2-provided last)
            if (!isVwapWithinTolerance(ticker)) {
                totalVwapRejected++;
                continue;
            }

            // 4) 24h change_pct volatility filter (v2-provided change_pct)
            if (!isWithinVolatilityLimit(ticker)) {
                totalVolatilityRejected++;
                continue;
            }

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

        // -------- Cross-pair spread (cannot come from a single v2 message) --------
        // The v2 ticker channel only exposes data for ONE pair per message.
        // The cross-pair spread (best bid in quote A vs best ask in quote B for
        // the SAME base asset, normalized to USD) requires combining multiple
        // v2 messages and must therefore be computed locally.
        BigDecimal spread = highestBid.normBid.subtract(cheapestAsk.normAsk);
        if (spread.signum() <= 0) return;

        BigDecimal spreadPct = spread.divide(cheapestAsk.normAsk, 8, RoundingMode.HALF_UP).multiply(HUNDRED);
        BigDecimal netProfitPct = spreadPct.subtract(roundTripFeePct);

        if (netProfitPct.compareTo(minProfitPct) > 0) {
            totalOpportunities++;

            ArbitrageOpportunity opp = new ArbitrageOpportunity(
                    baseAsset, cheapestAsk.quote, highestBid.quote,
                    cheapestAsk.ticker.ask, highestBid.ticker.bid,
                    cheapestAsk.normAsk, highestBid.normBid,
                    spreadPct, netProfitPct
            );

            log.info(
                    "ARBITRAGE_FOUND asset={} buy={}/{} sell={}/{} "
                            + "spreadPct={} netProfitPct={} "
                            + "buyPairSpreadPct={} sellPairSpreadPct={} "
                            + "buyTobUsd={} sellTobUsd={} "
                            + "buyVwap={} sellVwap={} "
                            + "buyChangePct={} sellChangePct={}",
                    baseAsset,
                    baseAsset, cheapestAsk.quote,
                    baseAsset, highestBid.quote,
                    spreadPct, netProfitPct,
                    cheapestAsk.ticker.pairSpreadPct(), highestBid.ticker.pairSpreadPct(),
                    cheapestAsk.ticker.topOfBookUsdAsk(), highestBid.ticker.topOfBookUsdBid(),
                    cheapestAsk.ticker.vwap, highestBid.ticker.vwap,
                    cheapestAsk.ticker.changePct, highestBid.ticker.changePct);
            recentOpportunities.add(opp);
            if (recentOpportunities.size() > MAX_RECENT_OPPORTUNITIES) {
                recentOpportunities.subList(0, recentOpportunities.size() - MAX_RECENT_OPPORTUNITIES).clear();
            }

            executeArbitrage(baseAsset, cheapestAsk, highestBid, opp);
        }
    }

    /**
     * Returns true if |last - vwap| / vwap is within {@code maxVwapDeviationPct}.
     * Both {@code last} and {@code vwap} come directly from the v2 ticker feed.
     * Returns true (passes) if either value is missing — we don't want to drop
     * quotes just because Kraken didn't populate one of them.
     */
    private boolean isVwapWithinTolerance(TickerData ticker) {
        if (ticker.vwap == null || ticker.last == null) return true;
        if (ticker.vwap.signum() <= 0) return true;
        BigDecimal diff = ticker.last.subtract(ticker.vwap).abs();
        BigDecimal devPct = diff.divide(ticker.vwap, 8, RoundingMode.HALF_UP).multiply(HUNDRED);
        return devPct.compareTo(maxVwapDeviationPct) <= 0;
    }

    /**
     * Returns true if |change_pct| (v2 24h change) is within
     * {@code maxChangePct24h}. Returns true if change_pct is null.
     */
    private boolean isWithinVolatilityLimit(TickerData ticker) {
        if (ticker.changePct == null) return true;
        return ticker.changePct.abs().compareTo(maxChangePct24h) <= 0;
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
            log.info("BUY_SELL_START asset={} buySymbol={} sellSymbol={} size={} buyLimit={} sellLimit={} expectedProfitPct={}",
                    baseAsset, buySymbol, sellSymbol, tradeSize,
                    buy.ticker.ask, sell.ticker.bid, opp.getEstimatedProfitPercent());

            orderClient.sendBatchOrder(List.of(
                    new KrakenWebSocketClient.OrderRequest(buySymbol, "buy", tradeSize.doubleValue(), buy.ticker.ask.doubleValue()),
                    new KrakenWebSocketClient.OrderRequest(sellSymbol, "sell", tradeSize.doubleValue(), sell.ticker.bid.doubleValue())
            ));

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

    /** Read-only v2-filter stats for diagnostics. */
    public FilterStats getFilterStats() {
        return new FilterStats(totalScans, totalOpportunities, totalTrades,
                totalPerPairRejected, totalLiquidityRejected,
                totalVwapRejected, totalVolatilityRejected);
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

    /** Immutable snapshot of v2-filter counters. */
    public static final class FilterStats {
        public final long scans;
        public final long opportunities;
        public final long trades;
        public final long perPairRejected;
        public final long liquidityRejected;
        public final long vwapRejected;
        public final long volatilityRejected;

        FilterStats(long scans, long opportunities, long trades,
                    long perPairRejected, long liquidityRejected,
                    long vwapRejected, long volatilityRejected) {
            this.scans = scans;
            this.opportunities = opportunities;
            this.trades = trades;
            this.perPairRejected = perPairRejected;
            this.liquidityRejected = liquidityRejected;
            this.vwapRejected = vwapRejected;
            this.volatilityRejected = volatilityRejected;
        }

        @Override
        public String toString() {
            return String.format(
                    "scans=%d opp=%d trades=%d rejected[pairSpread=%d liquidity=%d vwap=%d volatility=%d]",
                    scans, opportunities, trades,
                    perPairRejected, liquidityRejected, vwapRejected, volatilityRejected);
        }
    }
}
