package com.krakenfutures.wastebot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.krakenfutures.wastebot.agent.model.IndicatorSnapshot;
import com.krakenfutures.wastebot.agent.model.Signal;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.LocalTime;
import java.util.List;

public class FuturesSignalAgent {
    private static final Logger log = LoggerFactory.getLogger(FuturesSignalAgent.class);
    private final ObjectMapper objectMapper;

    public FuturesSignalAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Signal evaluate(List<Candle> candles, double balance, boolean existingPositionOpen) {
        if (candles == null || candles.size() < 22) {
            return Signal.none("Insufficient candle data", "MNQ");
        }

        BarSeries series = IndicatorCalculator.buildSeries("MNQ", candles);
        int index = series.getEndIndex();
        IndicatorSnapshot curr = IndicatorCalculator.calculateLatest(series);

        // Get previous indicators for crossover check
        BarSeries prevSubSeries = series.getSubSeries(0, index);
        IndicatorSnapshot prev = IndicatorCalculator.calculateLatest(prevSubSeries);

        double entryPrice = candles.get(candles.size() - 1).close();
        ZonedDateTime latestTime = ZonedDateTime.parse(candles.get(candles.size() - 1).time());
        ZonedDateTime estTime = latestTime.withZoneSameInstant(ZoneId.of("America/New_York"));
        LocalTime timeOfDay = estTime.toLocalTime();

        // 1. Session check: 09:30–16:00 EST ONLY
        boolean inSession = !timeOfDay.isBefore(LocalTime.of(9, 30)) && !timeOfDay.isAfter(LocalTime.of(16, 0));

        // Indicators
        double ema9 = curr.ema9();
        double ema21 = curr.ema21();
        double rsi = curr.rsi();
        double vwap = curr.vwap();
        double atr = curr.atr();
        double volumeRatio = curr.volumeRatio();

        // Previous indicators
        double prevEma9 = prev.ema9();
        double prevEma21 = prev.ema21();

        // Crossover check
        boolean emaCrossedUp = (prevEma9 <= prevEma21) && (ema9 > ema21);
        boolean emaCrossedDown = (prevEma9 >= prevEma21) && (ema9 < ema21);

        String signal = Signal.SIGNAL_NONE;
        String action = Signal.ACTION_WAIT;
        int confidence = 0;
        double sl = 0;
        double tp1 = 0;
        double tp2 = 0;
        int contracts = 0;
        double rr = 0;
        String reason = "Market sideways";
        boolean slTpVerified = false;
        boolean safeToTrade = false;
        String logLine;

        // Long entry conditions
        boolean longSignal = emaCrossedUp && (entryPrice > vwap) && (rsi >= 50 && rsi <= 65) && (volumeRatio >= 1.2);
        // Short entry conditions
        boolean shortSignal = emaCrossedDown && (entryPrice < vwap) && (rsi >= 35 && rsi <= 50) && (volumeRatio >= 1.2);

        if (existingPositionOpen) {
            return Signal.none("Position already open", "MNQ");
        }

        if (longSignal) {
            signal = Signal.SIGNAL_LONG;
            action = Signal.ACTION_ENTER;
            confidence = 75;
            sl = entryPrice - (atr * 1.5);
            tp1 = entryPrice + (atr * 1.0);
            tp2 = entryPrice + (atr * 2.25); // Set to 2.25 to guarantee R:R >= 1.5 to satisfy strict prompt rules
            rr = (tp2 - entryPrice) / (entryPrice - sl);
            slTpVerified = true;
            reason = "EMA golden cross";
        } else if (shortSignal) {
            signal = Signal.SIGNAL_SHORT;
            action = Signal.ACTION_ENTER;
            confidence = 75;
            sl = entryPrice + (atr * 1.5);
            tp1 = entryPrice - (atr * 1.0);
            tp2 = entryPrice - (atr * 2.25); // Set to 2.25 to guarantee R:R >= 1.5
            rr = (entryPrice - tp2) / (sl - entryPrice);
            slTpVerified = true;
            reason = "EMA death cross";
        }

        // POSITION SIZING
        if (slTpVerified && atr > 0) {
            double riskPct = 0.01;
            double riskAmount = balance * riskPct;
            double stopDistance = Math.abs(entryPrice - sl);
            if (stopDistance > 0) {
                contracts = (int) Math.floor(riskAmount / (stopDistance * 2.0));
                if (contracts < 1) contracts = 1;
                if (contracts > 5) contracts = 5;
            }
        }

        // Check reject conditions
        boolean reject = false;
        if (rsi > 70 || rsi < 30) {
            reason = "RSI extreme";
            reject = true;
        } else if (volumeRatio < 1.2) {
            reason = "Low volume ratio";
            reject = true;
        } else if (atr == 0) {
            reason = "ATR is zero";
            reject = true;
        } else if (confidence < 60) {
            reason = "Low confidence";
            reject = true;
        } else if (!inSession) {
            reason = "Outside market session";
            reject = true;
        }

        if (reject) {
            signal = Signal.SIGNAL_NONE;
            action = Signal.ACTION_WAIT;
            slTpVerified = false;
            safeToTrade = false;
            logLine = String.format("[SKIP] Reason=%s | RSI=%.1f | Confidence=%d", reason, rsi, confidence);
        } else if (signal.equals(Signal.SIGNAL_NONE)) {
            logLine = String.format("[TICK] %s | Price=%.2f | RSI=%.1f | EMA=%s",
                timeOfDay.toString(), entryPrice, rsi, (ema9 > ema21 ? "bullish" : "bearish"));
        } else {
            safeToTrade = true;
            logLine = String.format("[SIGNAL] %s | Confidence=%d%% | R:R=%.1f | Entry=%.2f",
                signal, confidence, rr, entryPrice);
        }

        // Final safety override
        if (!slTpVerified) {
            signal = Signal.SIGNAL_NONE;
        }
        if (!safeToTrade) {
            action = Signal.ACTION_WAIT;
        }

        return new Signal(
            signal,
            action,
            confidence,
            entryPrice,
            sl,
            tp1,
            tp2,
            0.0, // tp3 not used for futures
            contracts,
            rr,
            atr,
            "MNQ",
            reason,
            logLine,
            curr,
            slTpVerified,
            safeToTrade
        );
    }
}
