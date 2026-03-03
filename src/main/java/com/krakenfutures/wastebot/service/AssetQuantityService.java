package com.krakenfutures.wastebot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service to manage per-asset quantity settings.
 * Each asset maintains its own unique quantity - NO universal or default quantity is applied across all assets.
 */
@Service
public class AssetQuantityService {

    private static final Logger logger = LoggerFactory.getLogger(AssetQuantityService.class);

    // Default amount for all assets (used as fallback)
    @Value("${trading.default-amount:0.01}")
    private BigDecimal defaultAmount;

    // Per-asset quantity overrides - each asset maintains its own unique quantity setting
    private final Map<String, BigDecimal> assetQuantities = new ConcurrentHashMap<>();

    /**
     * Get the effective quantity for an asset.
     * Priority: 1) Per-asset override 2) Global default
     */
    public BigDecimal getQuantity(String asset) {
        BigDecimal assetQuantity = assetQuantities.get(asset.toUpperCase());
        return assetQuantity != null ? assetQuantity : defaultAmount;
    }

    /**
     * Set quantity for a specific asset - each asset has its own unique quantity setting.
     * The raw quantity is stored WITHOUT precision truncation.
     * Precision is applied at order placement time (in ScheduledTradingService.adjustAmountPrecision).
     * This prevents small values like 0.0002 from being truncated to 0 at storage time.
     */
    public void setQuantity(String asset, BigDecimal quantity, int amountPrecision) {
        String key = asset.toUpperCase();
        // Store the raw quantity - do NOT truncate here
        assetQuantities.put(key, quantity);
        // Log what the effective precision-adjusted value will be at order time
        BigDecimal previewAdjusted = quantity.setScale(amountPrecision, RoundingMode.DOWN);
        if (previewAdjusted.compareTo(BigDecimal.ZERO) == 0) {
            logger.warn("Set quantity for {}: {} (raw). WARNING: This rounds to 0 with amountPrecision={}! " +
                    "Increase the quantity or the asset's amountPrecision to avoid zero-amount orders.",
                    key, quantity, amountPrecision);
        } else {
            logger.info("Set unique quantity for {}: {} (raw, will be {} after precision adjustment at order time, overrides default {})",
                    key, quantity, previewAdjusted, defaultAmount);
        }
    }

    /**
     * Clear quantity override for an asset (revert to default)
     */
    public void clearQuantity(String asset) {
        String key = asset.toUpperCase();
        assetQuantities.remove(key);
        logger.info("Cleared quantity override for {}, will use default {}", key, defaultAmount);
    }

    /**
     * Get all asset quantities (overrides + defaults)
     */
    public Map<String, BigDecimal> getAllQuantities(Map<String, ?> supportedAssets) {
        Map<String, BigDecimal> result = new ConcurrentHashMap<>();
        for (Object entryObj : supportedAssets.keySet()) {
            String key = entryObj.toString();
            BigDecimal assetQuantity = assetQuantities.get(key);
            result.put(key, assetQuantity != null ? assetQuantity : defaultAmount);
        }
        return result;
    }

    /**
     * Get the default amount
     */
    public BigDecimal getDefaultAmount() {
        return defaultAmount;
    }

    /**
     * Set default amount
     */
    public void setDefaultAmount(BigDecimal amount) {
        this.defaultAmount = amount;
        logger.info("Default quantity set to: {}", amount);
    }

    /**
     * Check if an asset has a custom quantity override
     */
    public boolean hasOverride(String asset) {
        return assetQuantities.containsKey(asset.toUpperCase());
    }
}