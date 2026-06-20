//package com.krakenfutures.wastebot.agent;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.util.*;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//public class PerformanceAnalyzerAgent {
//    private static final Logger log = LoggerFactory.getLogger(PerformanceAnalyzerAgent.class);
//
//    public String analyzeLogs(List<String> logLines) {
//        if (logLines == null || logLines.isEmpty()) return "No log data to analyze.";
//
//        List<ParsedTrade> trades = new ArrayList<>();
//        List<SkipRecord> skips = new ArrayList<>();
//
//        Pattern enterPattern = Pattern.compile("\\[ENTER\\]\\s+(LONG|SHORT|BUY|SELL)\\s*\\|\\s*Entry=([0-9.]+)\\s*\\|\\s*SL=([0-9.]+)\\s*\\|\\s*TP1=([0-9.]+)\\s*\\|\\s*TP2=([0-9.]+)");
//        Pattern exitPattern = Pattern.compile("\\[EXIT\\]\\s+Price=([0-9.]+)\\s*\\|\\s*P&L=([+-]?[0-9.]+)\\s*\\|\\s*Duration=([0-9]+)min");
//        Pattern slHitPattern = Pattern.compile("\\[SL-HIT\\]\\s+Price=([0-9.]+)\\s*\\|\\s*P&L=([+-]?[0-9.]+)\\s*\\|\\s*Reason=([^|]+)");
//        Pattern tpHitPattern = Pattern.compile("\\[TP-HIT\\]\\s+(TP[12])\\s*\\|\\s*Price=([0-9.]+)\\s*\\|\\s*P&L=([+-]?[0-9.]+)");
//        Pattern skipPattern = Pattern.compile("\\[SKIP\\]\\s+Reason=([^|]+)");
//
//        ParsedTrade currentTrade = null;
//
//        for (String line : logLines) {
//            Matcher m;
//            if ((m = enterPattern.matcher(line)).find()) {
//                currentTrade = new ParsedTrade();
//                currentTrade.side = m.group(1);
//                currentTrade.entry = Double.parseDouble(m.group(2));
//                currentTrade.sl = Double.parseDouble(m.group(3));
//                currentTrade.tp1 = Double.parseDouble(m.group(4));
//                currentTrade.tp2 = Double.parseDouble(m.group(5));
//                currentTrade.entryTimeMs = System.currentTimeMillis();
//            } else if ((m = exitPattern.matcher(line)).find() && currentTrade != null) {
//                currentTrade.exit = Double.parseDouble(m.group(1));
//                currentTrade.pnl = Double.parseDouble(m.group(2));
//                currentTrade.durationMin = Integer.parseInt(m.group(3));
//                trades.add(currentTrade);
//                currentTrade = null;
//            } else if ((m = slHitPattern.matcher(line)).find() && currentTrade != null) {
//                currentTrade.exit = Double.parseDouble(m.group(1));
//                currentTrade.pnl = Double.parseDouble(m.group(2));
//                currentTrade.slHit = true;
//                trades.add(currentTrade);
//                currentTrade = null;
//            } else if ((m = tpHitPattern.matcher(line)).find() && currentTrade != null) {
//                String tpLevel = m.group(1);
//                currentTrade.pnl = Double.parseDouble(m.group(3));
//                if ("TP2".equals(tpLevel)) currentTrade.tp2Hit = true;
//            } else if ((m = skipPattern.matcher(line)).find()) {
//                skips.add(new SkipRecord(m.group(1)));
//            }
//        }
//
//        // Compute statistics
//        int totalTrades = trades.size();
//        int wins = 0;
//        double totalWins = 0, totalLosses = 0;
//        double bestTrade = Double.MIN_VALUE, worstTrade = Double.MAX_VALUE;
//        double sumDuration = 0;
//        int tp2Count = 0, slCount = 0;
//
//        for (ParsedTrade t : trades) {
//            if (t.pnl > 0) {
//                wins++;
//                totalWins += t.pnl;
//                if (t.pnl > bestTrade) bestTrade = t.pnl;
//            } else {
//                totalLosses += Math.abs(t.pnl);
//                if (t.pnl < worstTrade) worstTrade = t.pnl;
//            }
//            sumDuration += t.durationMin;
//            if (t.tp2Hit) tp2Count++;
//            if (t.slHit) slCount++;
//        }
//
//        double winRate = totalTrades > 0 ? (double) wins / totalTrades * 100 : 0;
//        double avgWin = wins > 0 ? totalWins / wins : 0;
//        double avgLoss = (totalTrades - wins) > 0 ? totalLosses / (totalTrades - wins) : 0;
//        double profitFactor = totalLosses > 0 ? totalWins / totalLosses : totalWins > 0 ? Double.POSITIVE_INFINITY : 0;
//        double slHitFreq = totalTrades > 0 ? (double) slCount / totalTrades * 100 : 0;
//        double tp2ReachRate = totalTrades > 0 ? (double) tp2Count / totalTrades * 100 : 0;
//
//        // Most common skip reason
//        Map<String, Integer> skipCounts = new HashMap<>();
//        for (SkipRecord s : skips) {
//            skipCounts.merge(s.reason.trim(), 1, Integer::sum);
//        }
//        String commonSkip = skipCounts.entrySet().stream()
//                .max(Map.Entry.comparingByValue())
//                .map(Map.Entry::getKey)
//                .orElse("none");
//
//        // Bot health
//        String health;
//        if (winRate >= 60 && profitFactor >= 1.5) health = "EXCELLENT";
//        else if (winRate >= 45 && profitFactor >= 1.0) health = "GOOD";
//        else if (winRate >= 30) health = "NEEDS_WORK";
//        else health = "CRITICAL";
//
//        StringBuilder sb = new StringBuilder();
//        sb.append("=== PERFORMANCE ANALYSIS ===\n\n");
//        sb.append("PERFORMANCE METRICS:\n");
//        sb.append("  Total trades:        ").append(totalTrades).append("\n");
//        sb.append(String.format("  Win rate:            %.1f%%\n", winRate));
//        sb.append(String.format("  Average win:         €%.2f\n", avgWin));
//        sb.append(String.format("  Average loss:        €%.2f\n", avgLoss));
//        sb.append(String.format("  Profit factor:       %.2f\n", profitFactor));
//        sb.append(String.format("  Best trade:          €%.2f\n", bestTrade == Double.MIN_VALUE ? 0 : bestTrade));
//        sb.append(String.format("  Worst trade:         €%.2f\n", worstTrade == Double.MAX_VALUE ? 0 : worstTrade));
//        sb.append(String.format("  Average duration:    %.0f minutes\n", totalTrades > 0 ? sumDuration / totalTrades : 0));
//        sb.append("\nPATTERN ANALYSIS:\n");
//        sb.append(String.format("  SL hit frequency:    %.1f%%\n", slHitFreq));
//        sb.append(String.format("  TP2 reach rate:      %.1f%%\n", tp2ReachRate));
//        sb.append("  Most common skip reason: ").append(commonSkip).append("\n");
//        sb.append("\nBOT HEALTH: ").append(health).append("\n");
//        sb.append("Continue trading: ").append(health.equals("CRITICAL") ? "false" : "true").append("\n");
//
//        return sb.toString();
//    }
//
//    private static class ParsedTrade {
//        String side;
//        double entry, sl, tp1, tp2, exit, pnl;
//        int durationMin;
//        boolean slHit, tp2Hit;
//        long entryTimeMs;
//    }
//
//    private record SkipRecord(String reason) {}
//}
