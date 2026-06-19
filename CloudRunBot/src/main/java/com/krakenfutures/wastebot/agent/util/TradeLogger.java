package com.krakenfutures.wastebot.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TradeLogger {
    private static final Logger log = LoggerFactory.getLogger(TradeLogger.class);

    private static final List<String> sessionLogs = Collections.synchronizedList(new ArrayList<>());

    public static void logTick(String timeframe, String asset, double price, double rsi, String state) {
        String msg = String.format("[TICK] %s | Asset=%s | Price=%.2f | RSI=%.1f | State=%s", timeframe, asset, price, rsi, state);
        logAndSave(msg);
    }

    public static void logSignal(String side, double cond, double rr, double entry) {
        String msg = String.format("[SIGNAL] %s | Confidence=%.0f%% | R:R=%.1f | Entry=%.2f", side, cond, rr, entry);
        logAndSave(msg);
    }

    public static void logEnter(String side, double entry, double sl, double tp1, double tp2) {
        String msg = String.format("[ENTER] %s | Entry=%.2f | SL=%.2f | TP1=%.2f | TP2=%.2f", side, entry, sl, tp1, tp2);
        logAndSave(msg);
    }

    public static void logExit(double price, double pnl, int durationMin) {
        String msg = String.format("[EXIT] Price=%.2f | P&L=%+$.2f | Duration=%dmin", price, pnl, durationMin);
        logAndSave(msg);
    }

    public static void logTpHit(String target, double price, double pnl) {
        String msg = String.format("[TP-HIT] %s | Price=%.2f | P&L=%+$.2f", target, price, pnl);
        logAndSave(msg);
    }

    public static void logSlHit(double price, double pnl, String reason) {
        String msg = String.format("[SL-HIT] Price=%.2f | P&L=%+$.2f | Reason=%s", price, pnl, reason);
        logAndSave(msg);
    }

    public static void logSkip(String reason, double rsi, int confidence) {
        String msg = String.format("[SKIP] Reason=%s | RSI=%.1f | Confidence=%d", reason, rsi, confidence);
        logAndSave(msg);
    }

    public static void logRotate(String asset, double momentum, double rsi) {
        String msg = String.format("[ROTATE] Best asset=%s | Momentum=%+.1f%% | RSI=%.1f", asset, momentum, rsi);
        logAndSave(msg);
    }

    public static void logHold(String asset, String reason) {
        String msg = String.format("[HOLD] %s | Signal=neutral | Reason=%s", asset, reason);
        logAndSave(msg);
    }

    public static void logRisk(boolean approved, double riskPct, String reason) {
        String msg = String.format("[RISK] Approved=%b | Risk=%.2f%% | Reason=%s", approved, riskPct, reason);
        logAndSave(msg);
    }

    public static void logError(String context, String error) {
        String msg = String.format("[ERROR] Context=%s | Message=%s", context, error);
        logAndSave(msg);
    }

    private static void logAndSave(String line) {
        log.info(line);
        sessionLogs.add(line);
    }

    public static List<String> getSessionLogs() {
        return new ArrayList<>(sessionLogs);
    }

    public static void clear() {
        sessionLogs.clear();
    }
}
