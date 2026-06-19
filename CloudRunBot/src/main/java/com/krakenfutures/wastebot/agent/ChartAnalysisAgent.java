package com.krakenfutures.wastebot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krakenfutures.wastebot.agent.client.AnthropicClient;
import com.krakenfutures.wastebot.agent.model.IndicatorSnapshot;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.io.IOException;
import java.util.List;

public class ChartAnalysisAgent {
    private static final Logger log = LoggerFactory.getLogger(ChartAnalysisAgent.class);

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    public ChartAnalysisAgent(ObjectMapper objectMapper, AnthropicClient anthropicClient) {
        this.objectMapper = objectMapper;
        this.anthropicClient = anthropicClient;
    }

    public String analyze(String instrument, String timeframe, List<Candle> candles) throws IOException {
        BarSeries series = IndicatorCalculator.buildSeries(instrument, candles);
        IndicatorSnapshot latestIndicators = IndicatorCalculator.calculateLatest(series);

        String systemPrompt = """
You are an expert technical analysis assistant specialized in futures and spot trading charts.

## ROLE
Analyze OHLCV candlestick charts and identify:
- Chart patterns (BB squeeze, V-shape, flags)
- Key support and resistance levels
- Indicator readings (RSI, BB, EMA, VWAP)
- Trade setups with entry, stop loss, take profit
- Market structure (trend, consolidation, reversal)

## INDICATORS TO READ
1. Bollinger Bands (20, 2, SMA)
   - Upper/Middle/Lower band values
   - BB squeeze detection (width < 75% of average)
   - Band expansion direction

2. RSI (14)
   - Overbought: > 70 (sell signal)
   - Oversold:   < 30 (buy signal)
   - Momentum:   50 crossover
   - Divergence: price vs RSI direction

3. EMA 9 / EMA 21
   - Crossover direction
   - Price position relative to EMAs
   - Trend confirmation

4. VWAP
   - Price above = bullish
   - Price below = bearish
   - Acts as dynamic support/resistance

5. Volume
   - Spike confirms breakout
   - Declining = weakening trend
   - Compare to 20-period average

## FIBONACCI LEVELS
Calculate from swing high to swing low:
  0.236, 0.382, 0.500, 0.618, 0.786, 1.000
  
Support zones:  0.382, 0.500, 0.618
Resistance zones: 0.786, 1.000

## PATTERN RECOGNITION
Identify these patterns:
- BB Squeeze → Breakout
- Fibonacci Retracement + Recovery
- V-Shape Recovery
- RSI Divergence (bullish/bearish)
- Volume climax (capitulation)
- VWAP reclaim

## TRADE PLAN OUTPUT FORMAT
For every chart analysis return:

MARKET SUMMARY:
  Instrument: [name]
  Timeframe: [15m/1H/4H]
  Trend: [bullish/bearish/sideways]
  Phase: [breakout/pullback/reversal/consolidation]

KEY LEVELS:
  Strong resistance: [price]
  Weak resistance: [price]
  Current price: [price]
  Weak support: [price]
  Strong support: [price]

TRADE SETUP:
  Direction: [LONG/SHORT]
  Entry: [price]
  Stop Loss: [price] ([X] points risk)
  TP1: [price] ([X] points = $[Y])
  TP2: [price] ([X] points = $[Y])
  R:R ratio: [X:1]
  Confidence: [0-100%]

INDICATOR READINGS:
  RSI: [value] [status]
  BB: [upper/middle/lower]
  EMA9/21: [bullish/bearish cross]
  Volume: [above/below average]
  VWAP: [price above/below]

WARNING FLAGS:
  [List any reasons NOT to trade]

DECISION:
  [BUY NOW / SELL NOW / WAIT / DO NOTHING]
  Reason: [max 15 words]

## STRICT RULES
- NEVER recommend trade with R:R < 1.5
- NEVER recommend BUY when RSI > 70
- NEVER recommend SELL when RSI < 30
- ALWAYS identify the nearest Fib level
- ALWAYS check volume before confirming breakout
- If chart is unclear → say WAIT, explain why
""";

        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Please analyze the following chart data and technical indicators computed from the last ").append(candles.size()).append(" periods:\n\n");
        userMsg.append("Instrument: ").append(instrument).append("\n");
        userMsg.append("Timeframe: ").append(timeframe).append("\n\n");
        
        userMsg.append("=== LATEST COMPUTED TECHNICAL INDICATORS ===\n");
        userMsg.append(String.format("Current Price: %.4f\n", candles.get(candles.size() - 1).close()));
        userMsg.append(String.format("EMA9: %.4f\n", latestIndicators.ema9()));
        userMsg.append(String.format("EMA21: %.4f\n", latestIndicators.ema21()));
        userMsg.append(String.format("RSI (14): %.2f\n", latestIndicators.rsi()));
        userMsg.append(String.format("VWAP: %.4f\n", latestIndicators.vwap()));
        userMsg.append(String.format("Bollinger Bands (20,2): Upper=%.4f, Middle=%.4f, Lower=%.4f\n", latestIndicators.bbUpper(), latestIndicators.bbMiddle(), latestIndicators.bbLower()));
        userMsg.append(String.format("Volume Ratio (vs 20 period SMA): %.2fx\n", latestIndicators.volumeRatio()));
        userMsg.append(String.format("ATR (14): %.4f\n\n", latestIndicators.atr()));

        userMsg.append("=== RECENT CANDLE DATA (OHLCV) ===\n");
        int start = Math.max(0, candles.size() - 40); // Send up to last 40 candles for pattern analysis
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            userMsg.append(String.format("Time: %s | O: %.4f | H: %.4f | L: %.4f | C: %.4f | V: %.2f\n",
                c.time(), c.open(), c.high(), c.low(), c.close(), c.volume()));
        }

        return anthropicClient.callClaude(systemPrompt, userMsg.toString());
    }
}
