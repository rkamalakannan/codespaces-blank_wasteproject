package cloudcode.krakenfutures.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized trading configuration.
 *
 * All sensitive credentials are read from environment variables.
 * Trading mode (paper/live) and market toggles (spot/futures) are
 * configurable via env vars or programmatic defaults.
 *
 * Environment variables:
 *   KRAKEN_API_KEY        — Spot API key
 *   KRAKEN_API_SECRET     — Spot API secret
 *   KRAKEN_FUTURES_KEY    — Derivatives API key
 *   KRAKEN_FUTURES_SECRET — Derivatives API secret
 *   TRADING_MODE          — "paper" (default) or "live"
 *   ENABLE_SPOT           — "true" (default) or "false"
 *   ENABLE_FUTURES        — "true" or "false" (default)
 *   TRADE_SIZE_USD        — base trade size in USD equivalent (default 50)
 *   MIN_PROFIT_PCT        — minimum net profit % to trigger trade (default 0.10)
 *   MAX_TICKER_AGE_MS     — max ticker staleness in ms (default 3000)
 *   TRADE_COOLDOWN_MS     — per-asset cooldown in ms (default 30000)
 *   MAX_CONCURRENT_TRADES — maximum number of active trades (default 1)
 *   MAX_PAIR_SPREAD_PCT   — max per-pair bid-ask spread % from v2 (default 1.50)
 *   MIN_TOP_OF_BOOK_USD   — min top-of-book USD liquidity (bid_qty*bid, default 25)
 *   MAX_VWAP_DEVIATION_PCT — max |last-vwap|/vwap % before flagging a quote (default 5.0)
 *   MAX_CHANGE_PCT_24H    — max |24h change_pct| allowed before skipping (default 25.0)
 *   MAX_MONITORED_ASSETS  — max number of assets to monitor via WebSocket (default 0 = all)
 *   MIN_24H_VOLUME_USD    — min 24h quote volume USD to include an asset (default 0 = no filter)
 *   MIN_24H_MOVE_PCT      — min 24h price move % to include an asset (default 0 = no filter)
 *   TAKER_FEE_PCT         — Kraken Spot taker fee % (default 0.40 — Pro $0+ tier)
 *   MAKER_FEE_PCT         — Kraken Spot maker fee % (default 0.25 — Pro $0+ tier)
 *   FEE_VOLUME_USD_30D    — 30-day volume tier for fee lookup (default 0 — Pro base)
 */
public class TradingConfig {

    private static final Logger log = LoggerFactory.getLogger(TradingConfig.class);

    public enum TradingMode { PAPER, LIVE }

    // --- Credentials (from env vars only) ---
    private final String spotApiKey;
    private final String spotApiSecret;
    private final String futuresApiKey;
    private final String futuresApiSecret;

    // --- Trading mode ---
    private final TradingMode tradingMode;

    // --- Market toggles ---
    private final boolean spotEnabled;
    private final boolean futuresEnabled;
    private final boolean leadLagEnabled;

    // --- Strategy parameters ---
    private final double tradeSizeUsd;
    private final double minProfitPct;
    private final long maxTickerAgeMs;
    private final long tradeCooldownMs;
    private final int maxConcurrentTrades;
    private final double maxPairSpreadPct;
    private final double minTopOfBookUsd;
    private final double maxVwapDeviationPct;
    private final double maxChangePct24h;
    private final int maxMonitoredAssets;
    private final double min24hVolumeUsd;
    private final double min24hMovePct;
    private final double takerFeePct;
    private final double makerFeePct;
    private final double feeVolumeUsd30d;

    private TradingConfig(Builder builder) {
        this.spotApiKey = builder.spotApiKey;
        this.spotApiSecret = builder.spotApiSecret;
        this.futuresApiKey = builder.futuresApiKey;
        this.futuresApiSecret = builder.futuresApiSecret;
        this.tradingMode = builder.tradingMode;
        this.spotEnabled = builder.spotEnabled;
        this.futuresEnabled = builder.futuresEnabled;
        this.leadLagEnabled = builder.leadLagEnabled;
        this.tradeSizeUsd = builder.tradeSizeUsd;
        this.minProfitPct = builder.minProfitPct;
        this.maxTickerAgeMs = builder.maxTickerAgeMs;
        this.tradeCooldownMs = builder.tradeCooldownMs;
        this.maxConcurrentTrades = builder.maxConcurrentTrades;
        this.maxPairSpreadPct = builder.maxPairSpreadPct;
        this.minTopOfBookUsd = builder.minTopOfBookUsd;
        this.maxVwapDeviationPct = builder.maxVwapDeviationPct;
        this.maxChangePct24h = builder.maxChangePct24h;
        this.maxMonitoredAssets = builder.maxMonitoredAssets;
        this.min24hVolumeUsd = builder.min24hVolumeUsd;
        this.min24hMovePct = builder.min24hMovePct;
        this.takerFeePct = builder.takerFeePct;
        this.makerFeePct = builder.makerFeePct;
        this.feeVolumeUsd30d = builder.feeVolumeUsd30d;
    }

    /**
     * Build config from environment variables with sensible defaults.
     */
    public static TradingConfig fromEnvironment() {
        Builder b = new Builder();

        // Credentials — strictly from env vars
        b.spotApiKey = envOrDefault("KRAKEN_API_KEY", "");
        b.spotApiSecret = envOrDefault("KRAKEN_API_SECRET", "");
        b.futuresApiKey = envOrDefault("KRAKEN_FUTURES_KEY", "");
        b.futuresApiSecret = envOrDefault("KRAKEN_FUTURES_SECRET", "");

        // Trading mode — default to PAPER for safety
        String mode = envOrDefault("TRADING_MODE", "paper");
        b.tradingMode = "live".equalsIgnoreCase(mode) ? TradingMode.LIVE : TradingMode.PAPER;

        // Market toggles
        b.spotEnabled = Boolean.parseBoolean(envOrDefault("ENABLE_SPOT", "true"));
        b.futuresEnabled = Boolean.parseBoolean(envOrDefault("ENABLE_FUTURES", "false"));
        b.leadLagEnabled = Boolean.parseBoolean(envOrDefault("ENABLE_LEADLAG", "false"));

        // Strategy parameters
        b.tradeSizeUsd = parseDouble(envOrDefault("TRADE_SIZE_USD", "50"), 50);
        b.minProfitPct = parseDouble(envOrDefault("MIN_PROFIT_PCT", "0.10"), 0.10);
        b.maxTickerAgeMs = parseLong(envOrDefault("MAX_TICKER_AGE_MS", "3000"), 3000);
        b.tradeCooldownMs = parseLong(envOrDefault("TRADE_COOLDOWN_MS", "30000"), 30000);
        b.maxConcurrentTrades = (int) parseLong(envOrDefault("MAX_CONCURRENT_TRADES", "1"), 1);

        // WebSocket v2-driven filters
        b.maxPairSpreadPct = parseDouble(envOrDefault("MAX_PAIR_SPREAD_PCT", "1.50"), 1.50);
        b.minTopOfBookUsd = parseDouble(envOrDefault("MIN_TOP_OF_BOOK_USD", "25"), 25);
        b.maxVwapDeviationPct = parseDouble(envOrDefault("MAX_VWAP_DEVIATION_PCT", "5.0"), 5.0);
        b.maxChangePct24h = parseDouble(envOrDefault("MAX_CHANGE_PCT_24H", "25.0"), 25.0);

        // Asset scanning limits — 0 means "no limit / scan all"
        b.maxMonitoredAssets = (int) parseLong(envOrDefault("MAX_MONITORED_ASSETS", "0"), 0);
        b.min24hVolumeUsd = parseDouble(envOrDefault("MIN_24H_VOLUME_USD", "0"), 0);
        b.min24hMovePct = parseDouble(envOrDefault("MIN_24H_MOVE_PCT", "0"), 0);

        // Kraken Pro Spot fee schedule (maker-taker, volume-based)
        // Defaults: Pro base tier ($0+ volume) — Maker 0.25%, Taker 0.40%
        b.takerFeePct = parseDouble(envOrDefault("TAKER_FEE_PCT", "0.40"), 0.40);
        b.makerFeePct = parseDouble(envOrDefault("MAKER_FEE_PCT", "0.25"), 0.25);
        b.feeVolumeUsd30d = parseDouble(envOrDefault("FEE_VOLUME_USD_30D", "0"), 0);

        TradingConfig config = new TradingConfig(b);
        config.logConfiguration();
        return config;
    }

    public void logConfiguration() {
        log.info("========================================");
        log.info("  TRADING CONFIGURATION");
        log.info("========================================");
        log.info("  Mode:            {}", tradingMode);
        log.info("  Spot enabled:    {}", spotEnabled);
        log.info("  Futures enabled: {}", futuresEnabled);
        log.info("  Lead-Lag enabled:{}", leadLagEnabled);
        log.info("  Spot API key:    {}", maskKey(spotApiKey));
        log.info("  Futures API key: {}", maskKey(futuresApiKey));
        log.info("  Trade size:      ${} USD", tradeSizeUsd);
        log.info("  Min profit:      {}%", minProfitPct);
        log.info("  Max ticker age:  {}ms", maxTickerAgeMs);
        log.info("  Trade cooldown:  {}ms", tradeCooldownMs);
        log.info("  Max conc trades: {}", maxConcurrentTrades);
        log.info("  Max pair spread: {}% (v2 filter)", maxPairSpreadPct);
        log.info("  Min top-of-book: ${} USD (v2 filter)", minTopOfBookUsd);
        log.info("  Max vwap dev:    {}% (v2 filter)", maxVwapDeviationPct);
        log.info("  Max 24h change:  {}% (v2 filter)", maxChangePct24h);
        log.info("  Max monitored:   {} assets (0=all)", maxMonitoredAssets);
        log.info("  Min 24h volume:  ${} USD (0=no filter)", min24hVolumeUsd);
        log.info("  Min 24h move:    {}% (0=no filter)", min24hMovePct);
        log.info("  Taker fee:       {}% (Kraken Pro Spot)", takerFeePct);
        log.info("  Maker fee:       {}% (Kraken Pro Spot)", makerFeePct);
        log.info("  30d fee volume:  ${} (fee tier)", feeVolumeUsd30d);
        if (tradingMode == TradingMode.PAPER) {
            log.info("  *** PAPER TRADING — no real orders will be placed ***");
        } else {
            log.warn("  *** LIVE TRADING — real orders WILL be placed ***");
        }
        log.info("========================================");
    }

    // --- Getters ---

    public String getSpotApiKey() { return spotApiKey; }
    public String getSpotApiSecret() { return spotApiSecret; }
    public String getFuturesApiKey() { return futuresApiKey; }
    public String getFuturesApiSecret() { return futuresApiSecret; }
    public TradingMode getTradingMode() { return tradingMode; }
    public boolean isPaperTrading() { return tradingMode == TradingMode.PAPER; }
    public boolean isLiveTrading() { return tradingMode == TradingMode.LIVE; }
    public boolean isSpotEnabled() { return spotEnabled; }
    public boolean isFuturesEnabled() { return futuresEnabled; }
    public boolean isLeadLagEnabled() { return leadLagEnabled; }
    public double getTradeSizeUsd() { return tradeSizeUsd; }
    public double getMinProfitPct() { return minProfitPct; }
    public long getMaxTickerAgeMs() { return maxTickerAgeMs; }
    public long getTradeCooldownMs() { return tradeCooldownMs; }
    public int getMaxConcurrentTrades() { return maxConcurrentTrades; }
    public double getMaxPairSpreadPct() { return maxPairSpreadPct; }
    public double getMinTopOfBookUsd() { return minTopOfBookUsd; }
    public double getMaxVwapDeviationPct() { return maxVwapDeviationPct; }
    public double getMaxChangePct24h() { return maxChangePct24h; }
    public int getMaxMonitoredAssets() { return maxMonitoredAssets; }
    public double getMin24hVolumeUsd() { return min24hVolumeUsd; }
    public double getMin24hMovePct() { return min24hMovePct; }
    public double getTakerFeePct() { return takerFeePct; }
    public double getMakerFeePct() { return makerFeePct; }
    public double getFeeVolumeUsd30d() { return feeVolumeUsd30d; }

    // --- Helpers ---

    private static String envOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val.trim() : defaultValue;
    }

    private static double parseDouble(String val, double fallback) {
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static long parseLong(String val, long fallback) {
        try { return Long.parseLong(val); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "(not set)";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    public static class Builder {
        private String spotApiKey = "";
        private String spotApiSecret = "";
        private String futuresApiKey = "";
        private String futuresApiSecret = "";
        private TradingMode tradingMode = TradingMode.PAPER;
        private boolean spotEnabled = true;
        private boolean futuresEnabled = false;
        private boolean leadLagEnabled = false;
        private double tradeSizeUsd = 50;
        private double minProfitPct = 0.10;
        private long maxTickerAgeMs = 3000;
        private long tradeCooldownMs = 30000;
        private int maxConcurrentTrades = 1;
        private double maxPairSpreadPct = 1.50;
        private double minTopOfBookUsd = 25;
        private double maxVwapDeviationPct = 5.0;
        private double maxChangePct24h = 25.0;
        private int maxMonitoredAssets = 0;       // 0 = all
        private double min24hVolumeUsd = 0;        // 0 = no filter
        private double min24hMovePct = 0;           // 0 = no filter
        private double takerFeePct = 0.40;
        private double makerFeePct = 0.25;
        private double feeVolumeUsd30d = 0;

        public Builder spotApiKey(String k) { this.spotApiKey = k != null ? k : ""; return this; }
        public Builder spotApiSecret(String s) { this.spotApiSecret = s != null ? s : ""; return this; }
        public Builder futuresApiKey(String k) { this.futuresApiKey = k != null ? k : ""; return this; }
        public Builder futuresApiSecret(String s) { this.futuresApiSecret = s != null ? s : ""; return this; }
        public Builder tradingMode(TradingMode m) { this.tradingMode = m; return this; }
        public Builder spotEnabled(boolean e) { this.spotEnabled = e; return this; }
        public Builder futuresEnabled(boolean e) { this.futuresEnabled = e; return this; }
        public Builder leadLagEnabled(boolean e) { this.leadLagEnabled = e; return this; }
        public Builder tradeSizeUsd(double s) { this.tradeSizeUsd = s; return this; }
        public Builder minProfitPct(double p) { this.minProfitPct = p; return this; }
        public Builder maxTickerAgeMs(long ms) { this.maxTickerAgeMs = ms; return this; }
        public Builder tradeCooldownMs(long ms) { this.tradeCooldownMs = ms; return this; }
        public Builder maxConcurrentTrades(int n) { this.maxConcurrentTrades = n; return this; }
        public Builder maxPairSpreadPct(double p) { this.maxPairSpreadPct = p; return this; }
        public Builder minTopOfBookUsd(double u) { this.minTopOfBookUsd = u; return this; }
        public Builder maxVwapDeviationPct(double p) { this.maxVwapDeviationPct = p; return this; }
        public Builder maxChangePct24h(double p) { this.maxChangePct24h = p; return this; }
        public Builder maxMonitoredAssets(int n) { this.maxMonitoredAssets = n; return this; }
        public Builder min24hVolumeUsd(double v) { this.min24hVolumeUsd = v; return this; }
        public Builder min24hMovePct(double p) { this.min24hMovePct = p; return this; }
        public Builder takerFeePct(double f) { this.takerFeePct = f; return this; }
        public Builder makerFeePct(double f) { this.makerFeePct = f; return this; }
        public Builder feeVolumeUsd30d(double v) { this.feeVolumeUsd30d = v; return this; }

        public TradingConfig build() { return new TradingConfig(this); }
    }

    /**
     * Kraken Pro Spot fee schedule lookup.
     * Given 30-day trading volume in USD, returns the maker and taker fee % as
     * a {@code double[]} of size 2: {@code [makerPct, takerPct]}.
     *
     * Source: https://www.kraken.com/features/fee-schedule (Spot Crypto Pro)
     */
    public static double[] feeTierForVolumeUsd30d(double volumeUsd) {
        if (volumeUsd >= 500_000_000d) return new double[]{0.00, 0.05};
        if (volumeUsd >= 100_000_000d) return new double[]{0.00, 0.08};
        if (volumeUsd >=  10_000_000d) return new double[]{0.00, 0.10};
        if (volumeUsd >=   5_000_000d) return new double[]{0.02, 0.12};
        if (volumeUsd >=   2_500_000d) return new double[]{0.04, 0.14};
        if (volumeUsd >=   1_000_000d) return new double[]{0.06, 0.16};
        if (volumeUsd >=     500_000d) return new double[]{0.08, 0.18};
        if (volumeUsd >=     250_000d) return new double[]{0.10, 0.20};
        if (volumeUsd >=     100_000d) return new double[]{0.12, 0.22};
        if (volumeUsd >=      50_000d) return new double[]{0.14, 0.24};
        if (volumeUsd >=      10_000d) return new double[]{0.20, 0.35};
        return new double[]{0.25, 0.40}; // $0+ tier (default)
    }

    /** Effective taker fee % for this config (uses feeVolumeUsd30d if explicitly set). */
    public double effectiveTakerFeePct() {
        if (feeVolumeUsd30d > 0) return feeTierForVolumeUsd30d(feeVolumeUsd30d)[1];
        return takerFeePct;
    }

    /** Effective maker fee % for this config. */
    public double effectiveMakerFeePct() {
        if (feeVolumeUsd30d > 0) return feeTierForVolumeUsd30d(feeVolumeUsd30d)[0];
        return makerFeePct;
    }
}
