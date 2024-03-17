package cloudcode.krakenfutures.models;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents a detected cross-currency arbitrage opportunity.
 * When the same asset (e.g., BTC) is priced differently across currency pairs
 * (e.g., BTC/USD vs BTC/EUR) after normalizing via exchange rates,
 * this captures the buy-cheap / sell-expensive opportunity.
 */
public class ArbitrageOpportunity {

    private final String baseAsset;
    private final String buyCurrency;
    private final String sellCurrency;
    private final BigDecimal buyPrice;
    private final BigDecimal sellPrice;
    private final BigDecimal buyPriceNormalized;
    private final BigDecimal sellPriceNormalized;
    private final BigDecimal spreadPercent;
    private final BigDecimal estimatedProfitPercent;
    private final Instant detectedAt;

    public ArbitrageOpportunity(String baseAsset, String buyCurrency, String sellCurrency,
                                BigDecimal buyPrice, BigDecimal sellPrice,
                                BigDecimal buyPriceNormalized, BigDecimal sellPriceNormalized,
                                BigDecimal spreadPercent, BigDecimal estimatedProfitPercent) {
        this.baseAsset = baseAsset;
        this.buyCurrency = buyCurrency;
        this.sellCurrency = sellCurrency;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.buyPriceNormalized = buyPriceNormalized;
        this.sellPriceNormalized = sellPriceNormalized;
        this.spreadPercent = spreadPercent;
        this.estimatedProfitPercent = estimatedProfitPercent;
        this.detectedAt = Instant.now();
    }

    public String getBaseAsset() {
        return baseAsset;
    }

    public String getBuyCurrency() {
        return buyCurrency;
    }

    public String getSellCurrency() {
        return sellCurrency;
    }

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public BigDecimal getSellPrice() {
        return sellPrice;
    }

    public BigDecimal getBuyPriceNormalized() {
        return buyPriceNormalized;
    }

    public BigDecimal getSellPriceNormalized() {
        return sellPriceNormalized;
    }

    public BigDecimal getSpreadPercent() {
        return spreadPercent;
    }

    public BigDecimal getEstimatedProfitPercent() {
        return estimatedProfitPercent;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    @Override
    public String toString() {
        return String.format(
                "ARBITRAGE [%s]: BUY on %s/%s @ %s (norm: %s) -> SELL on %s/%s @ %s (norm: %s) | spread=%.4f%% profit=%.4f%%",
                baseAsset, baseAsset, buyCurrency, buyPrice, buyPriceNormalized,
                baseAsset, sellCurrency, sellPrice, sellPriceNormalized,
                spreadPercent, estimatedProfitPercent
        );
    }
}
