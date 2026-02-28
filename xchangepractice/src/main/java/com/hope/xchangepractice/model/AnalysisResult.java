package com.hope.xchangepractice.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * The full technical-analysis result for a trading pair at a given moment.
 *
 * <p>All indicator values are computed by Ta4j on the most recent bar of the
 * fetched {@code BarSeries}.  The {@code signal} field summarises the
 * combined indicator consensus into a human-readable trading recommendation.
 */
@Data
@Builder
public class AnalysisResult {

    /** Trading pair, e.g. "BTC/USDT" */
    private String symbol;

    /** Timestamp of the most recent bar used for analysis */
    private ZonedDateTime timestamp;

    /** Latest closing price */
    private BigDecimal currentPrice;

    // ── Trend indicators ────────────────────────────────────────────────────

    /** Exponential Moving Average (short period = 9) */
    private BigDecimal ema9;

    /** Exponential Moving Average (long period = 21) */
    private BigDecimal ema21;

    /** Simple Moving Average (50 periods) */
    private BigDecimal sma50;

    // ── Momentum indicators ─────────────────────────────────────────────────

    /**
     * Relative Strength Index (14 periods).
     * <ul>
     *   <li>&gt; 70 → overbought (potential sell)</li>
     *   <li>&lt; 30 → oversold  (potential buy)</li>
     * </ul>
     */
    private BigDecimal rsi14;

    // ── MACD ────────────────────────────────────────────────────────────────

    /**
     * MACD line = EMA(12) − EMA(26).
     * Positive and rising → bullish momentum.
     */
    private BigDecimal macdLine;

    /**
     * MACD Signal line = EMA(9) of MACD line.
     * MACD crossing above signal → buy; below → sell.
     */
    private BigDecimal macdSignal;

    /** MACD Histogram = MACD line − Signal line */
    private BigDecimal macdHistogram;

    // ── Bollinger Bands ─────────────────────────────────────────────────────

    /** Bollinger Band upper boundary (SMA20 + 2σ) */
    private BigDecimal bollingerUpper;

    /** Bollinger Band middle line (SMA 20) */
    private BigDecimal bollingerMiddle;

    /** Bollinger Band lower boundary (SMA20 − 2σ) */
    private BigDecimal bollingerLower;

    // ── Signal ──────────────────────────────────────────────────────────────

    /**
     * Combined trading signal derived from all indicators.
     * Possible values: {@code BUY}, {@code SELL}, {@code HOLD}.
     */
    private String signal;

    /** Human-readable explanation of why the signal was generated */
    private String signalReason;
}
