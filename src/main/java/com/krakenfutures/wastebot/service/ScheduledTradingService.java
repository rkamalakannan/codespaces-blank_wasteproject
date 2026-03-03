package com.krakenfutures.wastebot.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.krakenfutures.dto.marketData.KrakenFuturesTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.krakenfutures.wastebot.weblayer.KrakenFutureConfiguration;

import jakarta.annotation.PostConstruct;

/**
 * Scheduled trading service that executes the arbitrage strategy 
 * at configured intervals. Polling frequency is optimized for:
 * - Market volatility (faster during volatile periods)
 * - API rate limits (respects Kraken limits)
 * - Execution speed (processes multiple assets efficiently)
 */
@Service
@ConditionalOnProperty(name = "trading.scheduler.enabled", havingValue = "true", matchIfMissing = false)
public class ScheduledTradingService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledTradingService.class);

    @Autowired
    private KrakenFutureConfiguration krakenConfiguration;

    @Autowired(required = false)
    private AssetQuantityService assetQuantityService;

    // Configurable polling interval (default 30 seconds - balanced for crypto markets)
    @Value("${trading.scheduler.interval-ms:30000}")
    private long pollingIntervalMs;

    // Enable/disable scheduler
    @Value("${trading.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    // Default amount for each trade
    @Value("${trading.default-amount:0.01}")
    private BigDecimal defaultAmount;

    // Maximum positions allowed
    @Value("${trading.max-positions:3}")
    private int maxPositions;

    // Open order expiry: unfilled orders older than this are automatically cancelled (0 = disabled)
    @Value("${trading.order.expiry-ms:300000}")
    private long orderExpiryMs;

    // Track scheduler state
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    // Track last trade time per asset to prevent over-trading
    private final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
    private final Map<String, String> lastTradeResult = new ConcurrentHashMap<>();

    // Minimum time between trades for same asset (milliseconds)
    private static final long MIN_TRADE_INTERVAL_MS = 60000; // 1 minute

    // Supported assets with decimal precision for amounts.
    // amountPrecision: number of decimal places supported for order quantities.
    // Set high enough to support small amounts like 0.0002 (needs at least 4 decimal places).
    public static final Map<String, AssetConfig> SUPPORTED_ASSETS = Map.ofEntries(
        Map.entry("BTC",  new AssetConfig("BTC",  "USD", 8, 4)),  // supports 0.0001 minimum
        Map.entry("ETH",  new AssetConfig("ETH",  "USD", 8, 4)),  // supports 0.0001 minimum
        Map.entry("SOL",  new AssetConfig("SOL",  "USD", 9, 4)),
        Map.entry("XRP",  new AssetConfig("XRP",  "USD", 6, 2)),
        Map.entry("ADA",  new AssetConfig("ADA",  "USD", 6, 2)),
        Map.entry("DOT",  new AssetConfig("DOT",  "USD", 10, 3)),
        Map.entry("DOGE", new AssetConfig("DOGE", "USD", 8, 2)),
        Map.entry("AVAX", new AssetConfig("AVAX", "USD", 8, 4)),
        Map.entry("MATIC",new AssetConfig("MATIC","USD", 10, 3)),
        Map.entry("LINK", new AssetConfig("LINK", "USD", 10, 4)),
        Map.entry("UNI",  new AssetConfig("UNI",  "USD", 10, 3)),
        Map.entry("LTC",  new AssetConfig("LTC",  "USD", 8, 4)),
        Map.entry("BCH",  new AssetConfig("BCH",  "USD", 8, 4)),
        Map.entry("ATOM", new AssetConfig("ATOM", "USD", 10, 4)),
        Map.entry("KSM",  new AssetConfig("KSM",  "USD", 10, 3)),
        Map.entry("GMT",  new AssetConfig("GMT",  "USD", 10, 2))
    );

    // Active assets to trade (configurable)
    @Value("${trading.active-assets:BTC,ETH,SOL}")
    private List<String> activeAssets;

    @PostConstruct
    public void init() {
        logger.info("=== Scheduled Trading Service Initialized ===");
        logger.info("Polling interval: {}ms ({} seconds)", pollingIntervalMs, pollingIntervalMs / 1000);
        logger.info("Scheduler enabled: {}", schedulerEnabled);
        logger.info("Default trade amount: {}", defaultAmount);
        logger.info("Max positions allowed: {}", maxPositions);
        logger.info("Min trade interval: {}ms", MIN_TRADE_INTERVAL_MS);
        logger.info("Order expiry: {}ms ({} seconds) - {}",
                orderExpiryMs, orderExpiryMs / 1000,
                orderExpiryMs <= 0 ? "DISABLED" : "ENABLED");
        logger.info("Active assets: {}", activeAssets);
    }

    /**
     * Scheduled task to ensure every open position has both a stop-loss and a take-profit order.
     * Runs at the same interval as the main trading cycle.
     * If a protective order is missing, it is automatically placed.
     */
    @Scheduled(fixedDelayString = "${trading.scheduler.interval-ms:30000}")
    public void ensureProtectiveOrders() {
        if (!schedulerEnabled || isPaused.get()) {
            logger.debug("[PROTECT_TASK] Scheduler paused or disabled, skipping protective orders check.");
            return;
        }

        logger.info("[PROTECT_TASK] Running protective orders check (stop-loss + take-profit for all open positions)...");
        try {
            int placed = krakenConfiguration.ensureProtectiveOrders();
            if (placed > 0) {
                logger.info("[PROTECT_TASK] Placed {} missing protective order(s).", placed);
            } else {
                logger.info("[PROTECT_TASK] All open positions already have protective orders.");
            }
        } catch (Exception e) {
            logger.error("[PROTECT_TASK] Error during protective orders check: {}", e.getMessage(), e);
        }
    }

    /**
     * Scheduled task to cancel unfilled open orders that have exceeded the configured expiry period.
     * Runs at the same interval as the main trading cycle.
     * Controlled by {@code trading.order.expiry-ms} property (0 = disabled).
     */
    @Scheduled(fixedDelayString = "${trading.scheduler.interval-ms:30000}")
    public void cancelExpiredOrders() {
        if (!schedulerEnabled || isPaused.get()) {
            logger.debug("[EXPIRY_TASK] Scheduler paused or disabled, skipping expiry check.");
            return;
        }

        if (orderExpiryMs <= 0) {
            logger.debug("[EXPIRY_TASK] Order expiry disabled (trading.order.expiry-ms={}). Skipping.", orderExpiryMs);
            return;
        }

        logger.info("[EXPIRY_TASK] Running open order expiry check (expiry={}ms / {}s)...",
                orderExpiryMs, orderExpiryMs / 1000);
        try {
            int cancelled = krakenConfiguration.cancelExpiredOpenOrders(orderExpiryMs);
            if (cancelled > 0) {
                logger.info("[EXPIRY_TASK] Cancelled {} expired unfilled order(s).", cancelled);
            } else {
                logger.info("[EXPIRY_TASK] No expired orders found.");
            }
        } catch (Exception e) {
            logger.error("[EXPIRY_TASK] Error during open order expiry check: {}", e.getMessage(), e);
        }
    }

    /**
     * Main scheduled execution method.
     * Runs at the configured interval to check for trading opportunities.
     */
    @Scheduled(fixedDelayString = "${trading.scheduler.interval-ms:30000}")
    public void executeScheduledTrade() {
        if (!schedulerEnabled || isPaused.get()) {
            logger.debug("Scheduler paused or disabled, skipping execution");
            return;
        }

        if (isRunning.get()) {
            logger.warn("Previous execution still running, skipping this cycle");
            return;
        }

        isRunning.set(true);
        long startTime = System.currentTimeMillis();

        try {
            logger.info("=== Starting Scheduled Trading Cycle ===");

            // Check global position cap before iterating assets
            if (!canTrade()) {
                logger.info("[CYCLE] Max positions reached ({}). No new orders will be placed this cycle.", maxPositions);
                return;
            }

            for (String asset : activeAssets) {
                // Per-asset rate-limit check (time-based cooldown)
                if (!canTradeAsset(asset)) {
                    continue;
                }

                try {
                    processAsset(asset);
                } catch (Exception e) {
                    logger.error("[CYCLE] Error processing asset {}: {}", asset, e.getMessage(), e);
                    lastTradeResult.put(asset, "ERROR: " + e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("=== Trading Cycle Complete in {}ms ===", duration);

        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Process a single asset for trading opportunities.
     * Guards are applied in this order:
     * 1. Asset support check
     * 2. Open position check via validateOrderPlacement() (API call)
     * 3. placeOrder() performs a second open position check as a safety net
     */
    private void processAsset(String asset) throws IOException {
        String result;

        try {
            // Check if asset is supported
            AssetConfig config = SUPPORTED_ASSETS.get(asset.toUpperCase());
            if (config == null) {
                logger.warn("[PROCESS] Asset {} is not supported. Skipping.", asset);
                result = "UNSUPPORTED";
                lastTradeResult.put(asset, result);
                return;
            }

            Instrument instrument = new org.knowm.xchange.currency.CurrencyPair(asset, "USD");

            // GUARD 1: Check if there's already an open position for this asset BEFORE fetching price data.
            // This is the primary guard to prevent placing new orders when a position already exists.
            logger.info("[PROCESS] {} - Checking for existing open position before order placement...", asset);
            KrakenFutureConfiguration.OrderResult validationResult = krakenConfiguration.validateOrderPlacement(instrument);
            if (!validationResult.isSuccess()) {
                result = "POSITION_EXISTS: " + validationResult.getMessage();
                logger.warn("[PROCESS] {} - BLOCKED by open position check. Reason: {}", asset, validationResult.getMessage());
                lastTradeResult.put(asset, result);
                return;
            }

            logger.info("[PROCESS] {} - No open position found. Proceeding to fetch price data and place order.", asset);

            // Get price data (only after confirming no open position)
            KrakenFuturesTicker futuresTicker = krakenConfiguration.getFuturesPriceChange(instrument);
            logger.info("[PROCESS] {} - Futures ticker fetched. Mark price: {}", asset,
                    futuresTicker != null ? futuresTicker.getMarkPrice() : "null");

            // Execute trade with per-asset quantity (each asset has its own unique quantity setting)
            BigDecimal quantity = getAssetQuantity(asset);
            BigDecimal amount = adjustAmountPrecision(config, quantity);
            logger.info("[PROCESS] {} - Placing order with amount: {}", asset, amount);

            // GUARD 2: placeOrder() itself also re-checks open positions as a safety net
            KrakenFutureConfiguration.OrderResult orderResult = krakenConfiguration.placeOrder(instrument, amount);

            if (orderResult != null && !orderResult.isSuccess()) {
                result = "ORDER_BLOCKED: " + orderResult.getMessage();
                logger.warn("[PROCESS] {} - Order was blocked inside placeOrder(). Reason: {}", asset, orderResult.getMessage());
            } else {
                lastTradeTime.put(asset, System.currentTimeMillis());
                result = "SUCCESS";
                logger.info("[PROCESS] {} - Trade executed successfully with amount: {}", asset, amount);
            }

        } catch (Exception e) {
            result = "FAILED: " + e.getMessage();
            logger.error("[PROCESS] {} - Trade failed with exception: {}", asset, e.getMessage(), e);
        }

        lastTradeResult.put(asset, result);
    }

    /**
     * Adjust amount precision based on asset configuration.
     * Throws an exception if the amount rounds to zero, preventing zero-amount orders.
     */
    private BigDecimal adjustAmountPrecision(AssetConfig config, BigDecimal amount) {
        BigDecimal adjusted = amount.setScale(config.getAmountPrecision(), RoundingMode.DOWN);
        if (adjusted.compareTo(BigDecimal.ZERO) == 0) {
            String minAmount = "0." + "0".repeat(config.getAmountPrecision() - 1) + "1";
            throw new IllegalArgumentException(
                    "Amount " + amount + " for asset " + config.getSymbol() +
                    " rounds to zero with amountPrecision=" + config.getAmountPrecision() +
                    ". Please set a larger quantity (minimum: " + minAmount + ").");
        }
        logger.info("[AMOUNT] {} - Raw amount: {}, Adjusted to {} decimal places: {}",
                config.getSymbol(), amount, config.getAmountPrecision(), adjusted);
        return adjusted;
    }

    /**
     * Check if we can open new positions globally (total position cap).
     * NOTE: This checks the TOTAL number of open positions across all assets.
     * Per-asset position checks are done in processAsset() via validateOrderPlacement().
     */
    private boolean canTrade() {
        try {
            int currentPositions = krakenConfiguration.getPositions().size();
            boolean allowed = currentPositions < maxPositions;
            logger.info("[CAN_TRADE] Current open positions: {}, Max allowed: {}, Trading allowed: {}",
                    currentPositions, maxPositions, allowed);
            return allowed;
        } catch (IOException e) {
            logger.error("[CAN_TRADE] Error fetching positions from exchange: {}. Blocking trade as safety measure.", e.getMessage(), e);
            // Fail safe: if we can't check positions, block trading to avoid over-exposure
            return false;
        }
    }

    /**
     * Check if we can trade a specific asset based on time-based rate limiting.
     * NOTE: This does NOT check for open positions - that is handled by validateOrderPlacement()
     * in processAsset(). This method only enforces the minimum time between trades.
     */
    private boolean canTradeAsset(String asset) {
        Long lastTrade = lastTradeTime.get(asset);
        if (lastTrade == null) {
            logger.debug("[CAN_TRADE_ASSET] {} - No previous trade recorded. Asset is eligible.", asset);
            return true;
        }

        long timeSinceLastTrade = System.currentTimeMillis() - lastTrade;
        if (timeSinceLastTrade < MIN_TRADE_INTERVAL_MS) {
            long remainingMs = MIN_TRADE_INTERVAL_MS - timeSinceLastTrade;
            logger.info("[CAN_TRADE_ASSET] {} - In cooldown period. {}ms remaining before next trade allowed.",
                    asset, remainingMs);
            return false;
        }

        logger.debug("[CAN_TRADE_ASSET] {} - Cooldown period elapsed ({}ms since last trade). Asset is eligible.", asset, timeSinceLastTrade);
        return true;
    }

    // ==================== Control Methods ====================

    public void pauseScheduler() {
        isPaused.set(true);
        logger.info("Scheduler paused");
    }

    public void resumeScheduler() {
        isPaused.set(false);
        logger.info("Scheduler resumed");
    }

    public boolean isSchedulerRunning() {
        return isRunning.get();
    }

    public boolean isSchedulerPaused() {
        return isPaused.get();
    }

    public Map<String, String> getLastTradeResults() {
        return new ConcurrentHashMap<>(lastTradeResult);
    }

    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    public void setPollingIntervalMs(long intervalMs) {
        this.pollingIntervalMs = intervalMs;
        logger.info("Polling interval updated to {}ms", intervalMs);
    }

    /**
     * Get quantity for a specific asset - ensures each asset maintains its own specific quantity setting
     * Priority: 1) Per-asset override from AssetQuantityService 2) Global default
     */
    private BigDecimal getAssetQuantity(String asset) {
        if (assetQuantityService != null) {
            return assetQuantityService.getQuantity(asset);
        }
        return defaultAmount;
    }

    // ==================== Inner Class for Asset Configuration ====================
    
    public static class AssetConfig {
        private final String symbol;
        private final String quote;
        private final int pricePrecision;
        private final int amountPrecision;

        public AssetConfig(String symbol, String quote, int pricePrecision, int amountPrecision) {
            this.symbol = symbol;
            this.quote = quote;
            this.pricePrecision = pricePrecision;
            this.amountPrecision = amountPrecision;
        }

        public String getSymbol() { return symbol; }
        public String getQuote() { return quote; }
        public int getPricePrecision() { return pricePrecision; }
        public int getAmountPrecision() { return amountPrecision; }
    }
}
