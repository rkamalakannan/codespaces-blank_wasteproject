package com.krakenfutures.wastebot.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.account.OpenPosition;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.dto.marketdata.KrakenTicker;
import org.knowm.xchange.krakenfutures.dto.marketData.KrakenFuturesTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.krakenfutures.wastebot.service.KrakenOHLCService;
import com.krakenfutures.wastebot.service.ScheduledTradingService;
import com.krakenfutures.wastebot.service.KrakenOHLCService.OHLCData;
import com.krakenfutures.wastebot.weblayer.KrakenFutureConfiguration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Trading Bot", description = "API for executing trades and managing positions")
public class BotController {

    private static final Logger logger = LoggerFactory.getLogger(BotController.class);

    @Autowired
    KrakenFutureConfiguration krakenConfiguration;

    @Autowired(required = false)
    ScheduledTradingService scheduledTradingService;

    @Autowired(required = false)
    com.krakenfutures.wastebot.service.AssetQuantityService assetQuantityService;

    @Autowired(required = false)
    KrakenOHLCService krakenOHLCService;

    // Default amount for manual trades
    @Value("${trading.default-amount:0.01}")
    private BigDecimal defaultAmount;

    // Track if scheduler is available
    private boolean schedulerAvailable = false;

    @PostConstruct
    public void init() {
        schedulerAvailable = (scheduledTradingService != null);
    }

    // ==================== Manual Trading Endpoints ====================

    @PostMapping("/execute/{asset}/{amount}")
    @Operation(summary = "Execute trade", description = "Execute a trade for the specified asset with the given amount")
    public String executeTrade(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC")
            @PathVariable String asset,
            @Parameter(description = "Original amount to trade", example = "0.5")
            @PathVariable BigDecimal amount) throws IOException {
        String assetUpper = asset.toUpperCase();
        Instrument instrument = new CurrencyPair(assetUpper, "USD");

        logger.info("[MANUAL_TRADE] Received manual trade request for asset={}, amount={}", assetUpper, amount);

        // GUARD: Check if there's already an open position for this asset before placing any order
        logger.info("[MANUAL_TRADE] {} - Validating: checking for existing open position...", assetUpper);
        KrakenFutureConfiguration.OrderResult validationResult = krakenConfiguration.validateOrderPlacement(instrument);
        if (!validationResult.isSuccess()) {
            logger.warn("[MANUAL_TRADE] {} - BLOCKED by open position check. Reason: {}", assetUpper, validationResult.getMessage());
            return validationResult.getMessage();
        }

        logger.info("[MANUAL_TRADE] {} - Validation passed. No open position found. Proceeding with order.", assetUpper);

        // Adjust amount precision based on asset
        ScheduledTradingService.AssetConfig config =
            ScheduledTradingService.SUPPORTED_ASSETS.get(assetUpper);
        if (config != null) {
            amount = amount.setScale(config.getAmountPrecision(), BigDecimal.ROUND_DOWN);
            logger.info("[MANUAL_TRADE] {} - Amount adjusted to precision {}: {}", assetUpper, config.getAmountPrecision(), amount);
        }

        KrakenFutureConfiguration.OrderResult orderResult = krakenConfiguration.placeOrder(instrument, amount);
        if (orderResult != null && !orderResult.isSuccess()) {
            logger.warn("[MANUAL_TRADE] {} - Order blocked inside placeOrder(). Reason: {}", assetUpper, orderResult.getMessage());
            return "Order blocked: " + orderResult.getMessage();
        }

        logger.info("[MANUAL_TRADE] {} - Trade executed successfully with amount: {}", assetUpper, amount);
        return "Trade executed for " + assetUpper + " with amount " + amount;
    }

    @GetMapping("/positions")
    @Operation(summary = "Get open positions", description = "Retrieve all open positions")
    public List<OpenPosition> getOpenPositions() throws IOException {
        return krakenConfiguration.getPositions();
    }

    @GetMapping("/futures-price/{asset}")
    @Operation(summary = "Get futures price", description = "Get the current futures price for the specified asset")
    public KrakenFuturesTicker getFuturesPrice(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC") 
            @PathVariable String asset) throws IOException {
        Instrument instrument = new CurrencyPair(asset.toUpperCase(), "USD");
        return krakenConfiguration.getFuturesPriceChange(instrument);
    }

    @GetMapping("/spot-price/{asset}")
    @Operation(summary = "Get spot price", description = "Get the current spot price for the specified asset")
    public KrakenTicker getSpotPrice(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC") 
            @PathVariable String asset) throws IOException {
        Instrument instrument = new CurrencyPair(asset.toUpperCase(), "USD");
        return krakenConfiguration.getSpotPriceChange(instrument);
    }

    @GetMapping("/profit-limit/{asset}")
    @Operation(summary = "Get profit limit price", description = "Calculate the predicted profit limit price for the asset")
    public BigDecimal getProfitLimitPrice(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC") 
            @PathVariable String asset) throws IOException {
        Instrument instrument = new CurrencyPair(asset.toUpperCase(), "USD");
        return krakenConfiguration.getProfitLimitPrice(instrument);
    }

    @PostMapping("/cancel-orders/{asset}")
    @Operation(summary = "Cancel orders", description = "Cancel the first open order for the specified asset")
    public String cancelOrders(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC") 
            @PathVariable String asset) throws IOException {
        Instrument instrument = new CurrencyPair(asset.toUpperCase(), "USD");
        krakenConfiguration.cancelTopFirstOrder(instrument);
        return "Orders cancelled for " + asset;
    }

    // ==================== Scheduler Control Endpoints ====================

    @GetMapping("/scheduler/status")
    @Operation(summary = "Get scheduler status", description = "Get current status of the scheduled trading service")
    public Map<String, Object> getSchedulerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("available", schedulerAvailable);
        
        if (schedulerAvailable) {
            status.put("running", !scheduledTradingService.isSchedulerPaused());
            status.put("paused", scheduledTradingService.isSchedulerPaused());
            status.put("pollingIntervalMs", scheduledTradingService.getPollingIntervalMs());
            status.put("lastTradeResults", scheduledTradingService.getLastTradeResults());
            status.put("supportedAssets", ScheduledTradingService.SUPPORTED_ASSETS.keySet());
        }
        
        return status;
    }

    @PostMapping("/scheduler/start")
    @Operation(summary = "Start scheduler", description = "Start the scheduled trading service")
    public String startScheduler() {
        if (!schedulerAvailable) {
            return "Scheduler not available - check configuration";
        }
        scheduledTradingService.resumeScheduler();
        return "Scheduler started";
    }

    @PostMapping("/scheduler/stop")
    @Operation(summary = "Stop scheduler", description = "Pause the scheduled trading service")
    public String stopScheduler() {
        if (!schedulerAvailable) {
            return "Scheduler not available - check configuration";
        }
        scheduledTradingService.pauseScheduler();
        return "Scheduler paused";
    }

    @PostMapping("/scheduler/interval")
    @Operation(summary = "Update polling interval", description = "Update the polling interval in milliseconds")
    public String updateInterval(
            @Parameter(description = "Polling interval in milliseconds", example = "30000") 
            @RequestParam long intervalMs) {
        if (!schedulerAvailable) {
            return "Scheduler not available - check configuration";
        }
        
        // Validate interval (minimum 5 seconds, maximum 5 minutes)
        if (intervalMs < 5000) {
            return "Interval too short (minimum 5000ms)";
        }
        if (intervalMs > 300000) {
            return "Interval too long (maximum 300000ms)";
        }
        
        scheduledTradingService.setPollingIntervalMs(intervalMs);
        return "Polling interval updated to " + intervalMs + "ms";
    }

    @GetMapping("/assets")
    @Operation(summary = "Get supported assets", description = "Get list of all supported cryptocurrency assets")
    public Map<String, Object> getSupportedAssets() {
        Map<String, Object> result = new HashMap<>();
        
        Map<String, ScheduledTradingService.AssetConfig> assets =
            ScheduledTradingService.SUPPORTED_ASSETS;
        
        for (Map.Entry<String, ScheduledTradingService.AssetConfig> entry : assets.entrySet()) {
            Map<String, Object> assetInfo = new HashMap<>();
            assetInfo.put("symbol", entry.getValue().getSymbol());
            assetInfo.put("quote", entry.getValue().getQuote());
            assetInfo.put("pricePrecision", entry.getValue().getPricePrecision());
            assetInfo.put("amountPrecision", entry.getValue().getAmountPrecision());
            
            // Include per-asset quantity if available
            if (assetQuantityService != null) {
                assetInfo.put("quantity", assetQuantityService.getQuantity(entry.getKey()));
                assetInfo.put("hasOverride", assetQuantityService.hasOverride(entry.getKey()));
            }
            
            result.put(entry.getKey(), assetInfo);
        }
        
        return result;
    }

    // ==================== Per-Asset Quantity Endpoints ====================

    @PostMapping("/assets/{asset}/quantity/{quantity}")
    @Operation(summary = "Set asset quantity", description = "Set unique quantity for a specific asset - ensures NO universal quantity is applied")
    public String setAssetQuantity(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC")
            @PathVariable String asset,
            @Parameter(description = "Quantity to trade", example = "0.05")
            @PathVariable BigDecimal quantity) {
        
        if (assetQuantityService == null) {
            return "Asset quantity service not available";
        }
        
        ScheduledTradingService.AssetConfig config =
            ScheduledTradingService.SUPPORTED_ASSETS.get(asset.toUpperCase());
        if (config == null) {
            return "Unsupported asset: " + asset;
        }
        
        assetQuantityService.setQuantity(asset, quantity, config.getAmountPrecision());
        return "Quantity for " + asset + " set to " + quantity;
    }

    @GetMapping("/assets/{asset}/quantity")
    @Operation(summary = "Get asset quantity", description = "Get the quantity setting for a specific asset")
    public Map<String, Object> getAssetQuantity(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC")
            @PathVariable String asset) {
        
        Map<String, Object> result = new HashMap<>();
        
        if (assetQuantityService == null) {
            result.put("error", "Asset quantity service not available");
            return result;
        }
        
        ScheduledTradingService.AssetConfig config =
            ScheduledTradingService.SUPPORTED_ASSETS.get(asset.toUpperCase());
        if (config == null) {
            result.put("error", "Unsupported asset: " + asset);
            return result;
        }
        
        result.put("asset", asset.toUpperCase());
        result.put("quantity", assetQuantityService.getQuantity(asset));
        result.put("hasOverride", assetQuantityService.hasOverride(asset));
        result.put("defaultAmount", assetQuantityService.getDefaultAmount());
        
        return result;
    }

    @DeleteMapping("/assets/{asset}/quantity")
    @Operation(summary = "Clear asset quantity", description = "Clear the quantity override for an asset, reverting to default")
    public String clearAssetQuantity(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC")
            @PathVariable String asset) {
        
        if (assetQuantityService == null) {
            return "Asset quantity service not available";
        }
        
        ScheduledTradingService.AssetConfig config =
            ScheduledTradingService.SUPPORTED_ASSETS.get(asset.toUpperCase());
        if (config == null) {
            return "Unsupported asset: " + asset;
        }
        
        assetQuantityService.clearQuantity(asset);
        return "Quantity override cleared for " + asset + ", using default";
    }

    @GetMapping("/assets/quantities")
    @Operation(summary = "Get all asset quantities", description = "Get quantity settings for all supported assets")
    public Map<String, BigDecimal> getAllAssetQuantities() {
        if (assetQuantityService == null) {
            return Map.of();
        }
        return assetQuantityService.getAllQuantities(ScheduledTradingService.SUPPORTED_ASSETS);
    }

    // ==================== OHLC Data Endpoints for Backtesting ====================

    @GetMapping("/ohlc/{asset}")
    @Operation(summary = "Get OHLC data", description = "Get OHLC candlestick data for backtesting. Interval: 1m, 5m, 15m, 1h, 4h, 1D, 1W, 1M")
    public Map<String, Object> getOHLCData(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC")
            @PathVariable String asset,
            @Parameter(description = "OHLC interval (1m, 5m, 15m, 1h, 4h, 1D, 1W, 1M)", example = "1h")
            @RequestParam(defaultValue = "1h") String interval,
            @Parameter(description = "Start time in ISO format (optional)", example = "2025-01-01T00:00:00")
            @RequestParam(required = false) String startTime,
            @Parameter(description = "End time in ISO format (optional)", example = "2026-01-01T00:00:00")
            @RequestParam(required = false) String endTime) throws IOException {
        
        Map<String, Object> result = new HashMap<>();
        
        if (krakenOHLCService == null) {
            result.put("error", "OHLC service not available");
            return result;
        }
        
        CurrencyPair pair = new CurrencyPair(asset.toUpperCase(), "USD");
        KrakenOHLCService.Interval ohlcInterval = KrakenOHLCService.Interval.fromString(interval);
        
        try {
            List<OHLCData> ohlcData;
            
            if (startTime != null && endTime != null) {
                // Get data for specific date range
                java.time.LocalDateTime start = java.time.LocalDateTime.parse(startTime);
                java.time.LocalDateTime end = java.time.LocalDateTime.parse(endTime);
                ohlcData = krakenOHLCService.getOHLCDataForDateRange(pair, ohlcInterval, start, end);
            } else {
                // Get data for past year (default)
                ohlcData = krakenOHLCService.getOHLCDataForPastYear(pair, ohlcInterval);
            }
            
            result.put("asset", asset.toUpperCase());
            result.put("interval", interval);
            result.put("count", ohlcData.size());
            result.put("data", ohlcData);
            
        } catch (Exception e) {
            result.put("error", "Failed to retrieve OHLC data: " + e.getMessage());
        }
        
        return result;
    }

    @GetMapping("/ohlc/{asset}/latest")
    @Operation(summary = "Get latest OHLC candle", description = "Get the most recent OHLC candle for an asset")
    public Map<String, Object> getLatestOHLC(
            @Parameter(description = "Asset symbol (e.g., BTC, ETH)", example = "BTC")
            @PathVariable String asset,
            @Parameter(description = "OHLC interval (1m, 5m, 15m, 1h, 4h, 1D, 1W, 1M)", example = "1h")
            @RequestParam(defaultValue = "1h") String interval) throws IOException {
        
        Map<String, Object> result = new HashMap<>();
        
        if (krakenOHLCService == null) {
            result.put("error", "OHLC service not available");
            return result;
        }
        
        CurrencyPair pair = new CurrencyPair(asset.toUpperCase(), "USD");
        KrakenOHLCService.Interval ohlcInterval = KrakenOHLCService.Interval.fromString(interval);
        
        try {
            OHLCData latest = krakenOHLCService.getLatestOHLC(pair, ohlcInterval);
            result.put("asset", asset.toUpperCase());
            result.put("interval", interval);
            result.put("latest", latest);
        } catch (Exception e) {
            result.put("error", "Failed to retrieve latest OHLC: " + e.getMessage());
        }
        
        return result;
    }
}
