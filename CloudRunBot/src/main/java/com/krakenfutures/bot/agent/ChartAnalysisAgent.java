//package com.krakenfutures.wastebot.agent;
//
//import com.krakenfutures.wastebot.agent.model.IndicatorSnapshot;
//import com.krakenfutures.wastebot.agent.util.IndicatorCalculator;
//import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.ta4j.core.Bar;
//import org.ta4j.core.BarSeries;
//
//import java.util.ArrayList;
//import java.util.List;
//
//public class ChartAnalysisAgent {
//    private static final Logger log = LoggerFactory.getLogger(ChartAnalysisAgent.class);
//
//    public String analyze(String instrument, String timeframe, List<Candle> candles) {
//        if (candles == null || candles.size() < 25) {
//            return "Insufficient data: need at least 25 candles for analysis.";
//        }
//
//        BarSeries series = IndicatorCalculator.buildSeries(instrument, candles);
//        int index = series.getEndIndex();
//        IndicatorSnapshot curr = IndicatorCalculator.calculateLatest(series);
//
//        double price = candles.get(candles.size() - 1).close();
//        int lookback = Math.min(candles.size(), 60);
//
//        String trend = detectTrend(series, index);
//        String phase = detectPhase(series, index, curr);
//
//        double strongResistance = curr.bbUpper();
//        double weakResistance = curr.bbMiddle();
//        double weakSupport = curr.bbMiddle();
//        double strongSupport = curr.bbLower();
//
//        double[] fibLevels = calculateFibonacci(series, index - lookback + 1, index);
//        List<String> patterns = detectPatterns(series, index, curr, fibLevels);
//
//        String direction = "NONE";
//        double entry = 0, sl = 0, tp1 = 0, tp2 = 0, rr = 0;
//        int confidence = 0;
//
//        if (curr.ema9() > curr.ema21() && price > curr.vwap() && curr.rsi() > 50 && curr.rsi() < 65 && curr.volumeRatio() > 1.2) {
//            direction = "LONG";
//            entry = price;
//            sl = Math.max(curr.bbLower(), entry - curr.atr() * 1.5);
//            tp1 = entry + curr.atr() * 1.5;
//            tp2 = entry + curr.atr() * 3.0;
//            rr = (tp2 - entry) / (entry - sl);
//            confidence = 70;
//            if (patterns.contains("BB Squeeze")) confidence += 10;
//            if (patterns.contains("RSI Bullish Divergence")) confidence += 10;
//        } else if (curr.ema9() < curr.ema21() && price < curr.vwap() && curr.rsi() < 50 && curr.rsi() > 35 && curr.volumeRatio() > 1.2) {
//            direction = "SHORT";
//            entry = price;
//            sl = Math.min(curr.bbUpper(), entry + curr.atr() * 1.5);
//            tp1 = entry - curr.atr() * 1.5;
//            tp2 = entry - curr.atr() * 3.0;
//            rr = (entry - tp2) / (sl - entry);
//            confidence = 70;
//        }
//
//        List<String> warnings = new ArrayList<>();
//        if (curr.rsi() > 70) warnings.add("RSI overbought (" + String.format("%.1f", curr.rsi()) + ")");
//        if (curr.rsi() < 30) warnings.add("RSI oversold (" + String.format("%.1f", curr.rsi()) + ")");
//        if (curr.volumeRatio() < 1.0) warnings.add("Volume below average");
//        if (rr > 0 && rr < 1.5) warnings.add("R:R below 1.5");
//
//        String decision = "WAIT";
//        String decisionReason = "No clear high-confidence setup";
//        if (!direction.equals("NONE") && confidence >= 70 && rr >= 1.5 && warnings.isEmpty()) {
//            decision = direction.equals("LONG") ? "BUY NOW" : "SELL NOW";
//            decisionReason = patterns.isEmpty() ? "Trend + volume confluence" : String.join(", ", patterns);
//        } else if (!warnings.isEmpty()) {
//            decisionReason = String.join(" | ", warnings);
//        }
//
//        StringBuilder sb = new StringBuilder();
//        sb.append("=== CHART ANALYSIS ===\n\n");
//        sb.append("MARKET SUMMARY:\n");
//        sb.append("  Instrument: ").append(instrument).append("\n");
//        sb.append("  Timeframe:  ").append(timeframe).append("\n");
//        sb.append("  Trend:      ").append(trend).append("\n");
//        sb.append("  Phase:      ").append(phase).append("\n\n");
//        sb.append("KEY LEVELS:\n");
//        sb.append(String.format("  Strong resistance: %.2f\n", strongResistance));
//        sb.append(String.format("  Weak resistance:   %.2f\n", weakResistance));
//        sb.append(String.format("  Current price:     %.2f\n", price));
//        sb.append(String.format("  Weak support:      %.2f\n", weakSupport));
//        sb.append(String.format("  Strong support:    %.2f\n", strongSupport));
//        sb.append(String.format("\n  Fib 0.382: %.2f | 0.500: %.2f | 0.618: %.2f | 0.786: %.2f\n",
//                fibLevels[1], fibLevels[2], fibLevels[3], fibLevels[4]));
//        sb.append("\nPATTERNS DETECTED:\n");
//        if (patterns.isEmpty()) {
//            sb.append("  No significant patterns\n");
//        } else {
//            for (String p : patterns) sb.append("  - ").append(p).append("\n");
//        }
//        sb.append("\nTRADE SETUP:\n");
//        sb.append("  Direction:   ").append(direction).append("\n");
//        sb.append(String.format("  Entry:       %.2f\n", entry));
//        sb.append(String.format("  Stop Loss:   %.2f (%.2f points risk)\n", sl, Math.abs(entry - sl)));
//        sb.append(String.format("  TP1:         %.2f (%.2f points)\n", tp1, Math.abs(tp1 - entry)));
//        sb.append(String.format("  TP2:         %.2f (%.2f points)\n", tp2, Math.abs(tp2 - entry)));
//        sb.append(String.format("  R:R ratio:   %.1f:1\n", rr));
//        sb.append(String.format("  Confidence:  %d%%\n\n", confidence));
//        sb.append("INDICATOR READINGS:\n");
//        sb.append(String.format("  RSI:         %.1f %s\n", curr.rsi(), rsiStatus(curr.rsi())));
//        sb.append(String.format("  BB:          %.2f / %.2f / %.2f\n", curr.bbUpper(), curr.bbMiddle(), curr.bbLower()));
//        sb.append("  EMA9/21:     ").append(curr.ema9() > curr.ema21() ? "bullish cross" : "bearish cross").append("\n");
//        sb.append("  Volume:      ").append(curr.volumeRatio() > 1.2 ? "above average" : curr.volumeRatio() < 0.8 ? "below average" : "average").append("\n");
//        sb.append("  VWAP:        price ").append(price > curr.vwap() ? "above" : "below").append("\n\n");
//        sb.append("WARNING FLAGS:\n");
//        if (warnings.isEmpty()) {
//            sb.append("  None\n");
//        } else {
//            for (String w : warnings) sb.append("  - ").append(w).append("\n");
//        }
//        sb.append("\nDECISION:\n");
//        sb.append("  ").append(decision).append("\n");
//        sb.append("  Reason: ").append(decisionReason).append("\n");
//        return sb.toString();
//    }
//
//    private String detectTrend(BarSeries series, int index) {
//        IndicatorSnapshot curr = IndicatorCalculator.calculateLatest(series);
//        double emaDist = Math.abs(curr.ema9() - curr.ema21()) / curr.ema21() * 100;
//        if (currentPrice(series, index) > curr.ema9() && curr.ema9() > curr.ema21() && emaDist > 0.5) return "bullish";
//        if (currentPrice(series, index) < curr.ema9() && curr.ema9() < curr.ema21() && emaDist > 0.5) return "bearish";
//        return "sideways";
//    }
//
//    private String detectPhase(BarSeries series, int index, IndicatorSnapshot curr) {
//        double width = curr.bbUpper() - curr.bbLower();
//        double avgWidth = 0;
//        int count = 0;
//        for (int i = Math.max(0, index - 20); i <= index; i++) {
//            IndicatorSnapshot snap = IndicatorCalculator.calculateLatest(series.getSubSeries(0, i + 1));
//            avgWidth += snap.bbUpper() - snap.bbLower();
//            count++;
//        }
//        avgWidth /= Math.max(1, count);
//        if (avgWidth > 0 && width < avgWidth * 0.75) return "consolidation/squeeze";
//        if (Math.abs(curr.ema9() - curr.ema21()) / curr.ema21() > 0.02) return "breakout";
//        return "pullback/consolidation";
//    }
//
//    private double[] calculateFibonacci(BarSeries series, int start, int end) {
//        double swingHigh = Double.MIN_VALUE;
//        double swingLow = Double.MAX_VALUE;
//        for (int i = start; i <= end; i++) {
//            Bar bar = series.getBar(i);
//            swingHigh = Math.max(swingHigh, bar.getHighPrice().doubleValue());
//            swingLow = Math.min(swingLow, bar.getLowPrice().doubleValue());
//        }
//        double range = swingHigh - swingLow;
//        return new double[] {
//            swingLow,
//            swingLow + range * 0.382,
//            swingLow + range * 0.500,
//            swingLow + range * 0.618,
//            swingLow + range * 0.786,
//            swingHigh
//        };
//    }
//
//    private List<String> detectPatterns(BarSeries series, int index, IndicatorSnapshot curr, double[] fibLevels) {
//        List<String> patterns = new ArrayList<>();
//        double price = currentPrice(series, index);
//        double width = curr.bbUpper() - curr.bbLower();
//
//        double avgWidth = 0;
//        int count = 0;
//        for (int i = Math.max(0, index - 20); i <= index; i++) {
//            IndicatorSnapshot snap = IndicatorCalculator.calculateLatest(series.getSubSeries(0, i + 1));
//            avgWidth += snap.bbUpper() - snap.bbLower();
//            count++;
//        }
//        avgWidth /= Math.max(1, count);
//        if (avgWidth > 0 && width < avgWidth * 0.75) patterns.add("BB Squeeze");
//
//        String[] fibNames = {"0.382", "0.500", "0.618", "0.786"};
//        for (int i = 0; i < 4; i++) {
//            if (Math.abs(price - fibLevels[i + 1]) / fibLevels[i + 1] < 0.01) {
//                patterns.add("Price at Fib " + fibNames[i]);
//            }
//        }
//
//        if (series.getBarCount() >= 10) {
//            double[] closes = new double[10];
//            for (int i = 0; i < 10; i++) closes[i] = series.getBar(index - i).getClosePrice().doubleValue();
//            int trough = 0;
//            for (int i = 1; i < 10; i++) if (closes[i] < closes[trough]) trough = i;
//            if (trough > 3 && trough < 8) {
//                double drop = (closes[0] - closes[trough]) / closes[trough];
//                double recover = (closes[trough] - closes[9]) / closes[9];
//                if (drop > 0.02 && recover < -0.01) patterns.add("V-Shape Recovery");
//            }
//        }
//
//        IndicatorSnapshot prev = IndicatorCalculator.calculateLatest(series.getSubSeries(0, index));
//        if (curr.rsi() > 60 && price > curr.ema9() * 1.01 && prev.rsi() > curr.rsi()) {
//            patterns.add("Bearish RSI Divergence");
//        }
//        if (curr.rsi() < 40 && price < curr.ema9() * 0.99 && prev.rsi() < curr.rsi()) {
//            patterns.add("Bullish RSI Divergence");
//        }
//
//        if (curr.volumeRatio() > 2.5) patterns.add("Volume Climax");
//        if (price > curr.vwap() && currentPrice(series, index - 1) < prev.vwap()) patterns.add("VWAP Reclaim");
//        return patterns;
//    }
//
//    private double currentPrice(BarSeries series, int index) {
//        return series.getBar(Math.min(index, series.getEndIndex())).getClosePrice().doubleValue();
//    }
//
//    private String rsiStatus(double rsi) {
//        if (rsi > 70) return "[OVERBOUGHT]";
//        if (rsi < 30) return "[OVERSOLD]";
//        if (rsi > 50) return "[bullish momentum]";
//        return "[bearish momentum]";
//    }
//}
