package com.hope.xchangepractice.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * Represents a single OHLCV (Open-High-Low-Close-Volume) candlestick bar.
 *
 * <p>This is the raw market data unit consumed by Ta4j's {@code BarSeries}.
 * Each bar corresponds to one time-period (e.g. 1 hour) on a given trading pair.
 */
@Data
@Builder
public class CryptoBar {

    /** Trading pair symbol, e.g. "BTC/USDT" */
    private String symbol;

    /** Bar open time (start of the candle period) */
    private ZonedDateTime openTime;

    /** Price at the start of the period */
    private BigDecimal open;

    /** Highest price during the period */
    private BigDecimal high;

    /** Lowest price during the period */
    private BigDecimal low;

    /** Price at the end of the period */
    private BigDecimal close;

    /** Total volume traded during the period */
    private BigDecimal volume;
}
