package com.hope.xchangepractice.service;

import com.hope.xchangepractice.model.AnalysisResult;
import com.hope.xchangepractice.model.CryptoBar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DecimalNum;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;

/**
 * Core technical-analysis engine powered by <strong>Ta4j 0.16</strong>.
 *
 * <h2>Ta4j Concepts Used</h2>
 * <ul>
 *   <li>{@link BarSeries}   – an ordered, time-indexed sequence of OHLCV bars</li>
 *   <li>{@link Bar}         – a single OHLCV candlestick</li>
 *   <li>{@link ClosePriceIndicator} – extracts the close price from each bar</li>
 *   <li>{@link EMAIndicator}        – Exponential Moving Average</li>
 *   <li>{@link SMAIndicator}        – Simple Moving Average</li>
 *   <li>{@link RSIIndicator}        – Relative Strength Index</li>
 *   <li>{@link MACDIndicator}       – Moving Average Convergence/Divergence</li>
 *   <li>{@link BollingerBandsUpperIndicator} / Middle / Lower</li>
 * </ul>
 *
 * <h2>Signal Logic</h2>
 * <pre>
 *  BUY  when: RSI &lt; 35  AND  EMA9 &gt; EMA21  AND  MACD histogram &gt; 0
 *  SELL when: RSI &gt; 65  AND  EMA9 &lt; EMA21  AND  MACD histogram &lt; 0
 *  HOLD otherwise
 * </pre>
 */
@Slf4j
@Service
public class TechnicalAnalysisService {

    // ── Indicator periods ────────────────────────────────────────────────────

    private static final int    EMA_SHORT        = 9;
    private static final int    EMA_LONG         = 21;
    private static final int    SMA_PERIOD       = 50;
    private static final int    RSI_PERIOD       = 14;
    private static final int    MACD_SHORT       = 12;
    private static final int    MACD_LONG        = 26;
    private static final int    MACD_SIGNAL      = 9;
    private static final int    BOLLINGER_PERIOD = 20;
    private static final double BOLLINGER_K      = 2.0;   // standard deviations

    // ── RSI thresholds ───────────────────────────────────────────────────────

    private static final double RSI_OVERSOLD   = 35.0;
    private static final double RSI_OVERBOUGHT = 65.0;

    /**
     * Runs all Ta4j indicators on the supplied bars and returns a fully
     * populated {@link AnalysisResult}.
     *
     * @param bars ordered list of OHLCV bars (oldest first, newest last)
     * @return analysis result with indicator values and a trading signal
     */
    public AnalysisResult analyse(List<CryptoBar> bars) {

        // ── 1. Build Ta4j BarSeries ──────────────────────────────────────────
        //
        // BarSeries is the fundamental Ta4j data structure.
        // We give it a name (the trading pair) and add each CryptoBar as a
        // Ta4j BaseBar.  Ta4j uses its own Num type (DecimalNum) for precision.

        String symbol = bars.get(0).getSymbol();
        BarSeries series = new BaseBarSeries(symbol);

        for (CryptoBar cb : bars) {
            // Ta4j 0.16 BaseBar constructor:
            // BaseBar(Duration timePeriod, ZonedDateTime endTime,
            //         Num open, Num high, Num low, Num close, Num volume, Num amount)
            Bar bar = new BaseBar(
                    Duration.ofHours(1),
                    cb.getOpenTime().plusHours(1),          // endTime = openTime + 1h
                    DecimalNum.valueOf(cb.getOpen()),
                    DecimalNum.valueOf(cb.getHigh()),
                    DecimalNum.valueOf(cb.getLow()),
                    DecimalNum.valueOf(cb.getClose()),
                    DecimalNum.valueOf(cb.getVolume()),
                    DecimalNum.valueOf(0)                   // amount (trades value) — not used
            );
            series.addBar(bar);
        }

        int lastIndex = series.getEndIndex();
        log.debug("BarSeries '{}' built with {} bars; analysing index {}",
                symbol, series.getBarCount(), lastIndex);

        // ── 2. Close-price indicator (base for all others) ───────────────────
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // ── 3. Trend: EMA 9 and EMA 21 ──────────────────────────────────────
        //
        // EMA gives more weight to recent prices than SMA.
        // When EMA9 > EMA21 the short-term trend is bullish (golden cross).
        EMAIndicator ema9  = new EMAIndicator(closePrice, EMA_SHORT);
        EMAIndicator ema21 = new EMAIndicator(closePrice, EMA_LONG);

        // ── 4. Trend: SMA 50 ────────────────────────────────────────────────
        SMAIndicator sma50 = new SMAIndicator(closePrice, SMA_PERIOD);

        // ── 5. Momentum: RSI 14 ─────────────────────────────────────────────
        //
        // RSI measures the speed and magnitude of recent price changes.
        // Formula: RSI = 100 − (100 / (1 + RS))  where RS = avg gain / avg loss
        RSIIndicator rsi14 = new RSIIndicator(closePrice, RSI_PERIOD);

        // ── 6. Momentum: MACD ───────────────────────────────────────────────
        //
        // MACD line   = EMA(12) − EMA(26)
        // Signal line = EMA(9) of MACD line
        // Histogram   = MACD line − Signal line
        MACDIndicator macd       = new MACDIndicator(closePrice, MACD_SHORT, MACD_LONG);
        EMAIndicator  macdSignal = new EMAIndicator(macd, MACD_SIGNAL);

        // ── 7. Volatility: Bollinger Bands ───────────────────────────────────
        //
        // Middle = SMA(20)
        // Upper  = Middle + 2 × σ(20)
        // Lower  = Middle − 2 × σ(20)
        // Price near upper band → overbought; near lower band → oversold.
        SMAIndicator               sma20    = new SMAIndicator(closePrice, BOLLINGER_PERIOD);
        StandardDeviationIndicator stdDev20 = new StandardDeviationIndicator(closePrice, BOLLINGER_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsUpperIndicator  bbUpper  =
                new BollingerBandsUpperIndicator(bbMiddle, stdDev20, DecimalNum.valueOf(BOLLINGER_K));
        BollingerBandsLowerIndicator  bbLower  =
                new BollingerBandsLowerIndicator(bbMiddle, stdDev20, DecimalNum.valueOf(BOLLINGER_K));

        // ── 8. Extract values at the last bar ────────────────────────────────
        BigDecimal currentPrice  = round(closePrice.getValue(lastIndex));
        BigDecimal ema9Val       = round(ema9.getValue(lastIndex));
        BigDecimal ema21Val      = round(ema21.getValue(lastIndex));
        BigDecimal sma50Val      = round(sma50.getValue(lastIndex));
        BigDecimal rsi14Val      = round(rsi14.getValue(lastIndex));
        BigDecimal macdLineVal   = round(macd.getValue(lastIndex));
        BigDecimal macdSignalVal = round(macdSignal.getValue(lastIndex));
        BigDecimal macdHistogram = macdLineVal.subtract(macdSignalVal);
        BigDecimal bbUpperVal    = round(bbUpper.getValue(lastIndex));
        BigDecimal bbMiddleVal   = round(bbMiddle.getValue(lastIndex));
        BigDecimal bbLowerVal    = round(bbLower.getValue(lastIndex));

        // ── 9. Generate trading signal ───────────────────────────────────────
        String[] signalAndReason = generateSignal(
                rsi14Val, ema9Val, ema21Val, macdHistogram);

        log.info("[{}] price={} RSI={} EMA9={} EMA21={} MACD_hist={} → {}",
                symbol, currentPrice, rsi14Val, ema9Val, ema21Val,
                macdHistogram, signalAndReason[0]);

        return AnalysisResult.builder()
                .symbol(symbol)
                .timestamp(bars.get(lastIndex).getOpenTime())
                .currentPrice(currentPrice)
                .ema9(ema9Val)
                .ema21(ema21Val)
                .sma50(sma50Val)
                .rsi14(rsi14Val)
                .macdLine(macdLineVal)
                .macdSignal(macdSignalVal)
                .macdHistogram(macdHistogram)
                .bollingerUpper(bbUpperVal)
                .bollingerMiddle(bbMiddleVal)
                .bollingerLower(bbLowerVal)
                .signal(signalAndReason[0])
                .signalReason(signalAndReason[1])
                .build();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Derives a BUY / SELL / HOLD signal from the computed indicator values.
     *
     * <p>Signal rules (all conditions must be true for BUY or SELL):
     * <ul>
     *   <li><b>BUY</b>:  RSI &lt; {@value #RSI_OVERSOLD}
     *                    AND EMA9 &gt; EMA21 (short-term uptrend)
     *                    AND MACD histogram &gt; 0 (bullish momentum)</li>
     *   <li><b>SELL</b>: RSI &gt; {@value #RSI_OVERBOUGHT}
     *                    AND EMA9 &lt; EMA21 (short-term downtrend)
     *                    AND MACD histogram &lt; 0 (bearish momentum)</li>
     *   <li><b>HOLD</b>: all other cases</li>
     * </ul>
     *
     * @return two-element array: [signal, reason]
     */
    private String[] generateSignal(
            BigDecimal rsi, BigDecimal ema9, BigDecimal ema21, BigDecimal macdHist) {

        double  rsiD          = rsi.doubleValue();
        double  macdHistD     = macdHist.doubleValue();
        boolean goldenCross   = ema9.compareTo(ema21) > 0;   // EMA9 > EMA21 → bullish
        boolean deathCross    = ema9.compareTo(ema21) < 0;   // EMA9 < EMA21 → bearish

        if (rsiD < RSI_OVERSOLD && goldenCross && macdHistD > 0) {
            return new String[]{
                "BUY",
                String.format(
                    "RSI(%.2f) is oversold (<%.0f), EMA9(%.2f) > EMA21(%.2f) signals uptrend, " +
                    "MACD histogram(%.4f) is positive — bullish confluence.",
                    rsiD, RSI_OVERSOLD, ema9.doubleValue(), ema21.doubleValue(), macdHistD)
            };
        }

        if (rsiD > RSI_OVERBOUGHT && deathCross && macdHistD < 0) {
            return new String[]{
                "SELL",
                String.format(
                    "RSI(%.2f) is overbought (>%.0f), EMA9(%.2f) < EMA21(%.2f) signals downtrend, " +
                    "MACD histogram(%.4f) is negative — bearish confluence.",
                    rsiD, RSI_OVERBOUGHT, ema9.doubleValue(), ema21.doubleValue(), macdHistD)
            };
        }

        return new String[]{
            "HOLD",
            String.format(
                "No clear confluence: RSI=%.2f, EMA9 %s EMA21, MACD histogram=%.4f. " +
                "Wait for stronger signal.",
                rsiD,
                goldenCross ? ">" : (deathCross ? "<" : "="),
                macdHistD)
        };
    }

    /**
     * Converts a Ta4j {@link org.ta4j.core.num.Num} to a {@link BigDecimal}
     * rounded to 8 decimal places (standard crypto precision).
     */
    private BigDecimal round(org.ta4j.core.num.Num num) {
        return BigDecimal.valueOf(num.doubleValue())
                .setScale(8, RoundingMode.HALF_UP);
    }
}
