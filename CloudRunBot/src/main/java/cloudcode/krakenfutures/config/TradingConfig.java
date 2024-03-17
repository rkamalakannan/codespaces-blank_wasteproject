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

    // --- Strategy parameters ---
    private final double tradeSizeUsd;
    private final double minProfitPct;
    private final long maxTickerAgeMs;
    private final long tradeCooldownMs;

    private TradingConfig(Builder builder) {
        this.spotApiKey = builder.spotApiKey;
        this.spotApiSecret = builder.spotApiSecret;
        this.futuresApiKey = builder.futuresApiKey;
        this.futuresApiSecret = builder.futuresApiSecret;
        this.tradingMode = builder.tradingMode;
        this.spotEnabled = builder.spotEnabled;
        this.futuresEnabled = builder.futuresEnabled;
        this.tradeSizeUsd = builder.tradeSizeUsd;
        this.minProfitPct = builder.minProfitPct;
        this.maxTickerAgeMs = builder.maxTickerAgeMs;
        this.tradeCooldownMs = builder.tradeCooldownMs;
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

        // Strategy parameters
        b.tradeSizeUsd = parseDouble(envOrDefault("TRADE_SIZE_USD", "50"), 50);
        b.minProfitPct = parseDouble(envOrDefault("MIN_PROFIT_PCT", "0.10"), 0.10);
        b.maxTickerAgeMs = parseLong(envOrDefault("MAX_TICKER_AGE_MS", "3000"), 3000);
        b.tradeCooldownMs = parseLong(envOrDefault("TRADE_COOLDOWN_MS", "30000"), 30000);

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
        log.info("  Spot API key:    {}", maskKey(spotApiKey));
        log.info("  Futures API key: {}", maskKey(futuresApiKey));
        log.info("  Trade size:      ${} USD", tradeSizeUsd);
        log.info("  Min profit:      {}%", minProfitPct);
        log.info("  Max ticker age:  {}ms", maxTickerAgeMs);
        log.info("  Trade cooldown:  {}ms", tradeCooldownMs);
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
    public double getTradeSizeUsd() { return tradeSizeUsd; }
    public double getMinProfitPct() { return minProfitPct; }
    public long getMaxTickerAgeMs() { return maxTickerAgeMs; }
    public long getTradeCooldownMs() { return tradeCooldownMs; }

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
        private double tradeSizeUsd = 50;
        private double minProfitPct = 0.10;
        private long maxTickerAgeMs = 3000;
        private long tradeCooldownMs = 30000;

        public Builder spotApiKey(String k) { this.spotApiKey = k != null ? k : ""; return this; }
        public Builder spotApiSecret(String s) { this.spotApiSecret = s != null ? s : ""; return this; }
        public Builder futuresApiKey(String k) { this.futuresApiKey = k != null ? k : ""; return this; }
        public Builder futuresApiSecret(String s) { this.futuresApiSecret = s != null ? s : ""; return this; }
        public Builder tradingMode(TradingMode m) { this.tradingMode = m; return this; }
        public Builder spotEnabled(boolean e) { this.spotEnabled = e; return this; }
        public Builder futuresEnabled(boolean e) { this.futuresEnabled = e; return this; }
        public Builder tradeSizeUsd(double s) { this.tradeSizeUsd = s; return this; }
        public Builder minProfitPct(double p) { this.minProfitPct = p; return this; }
        public Builder maxTickerAgeMs(long ms) { this.maxTickerAgeMs = ms; return this; }
        public Builder tradeCooldownMs(long ms) { this.tradeCooldownMs = ms; return this; }

        public TradingConfig build() { return new TradingConfig(this); }
    }
}
