//package com.krakenfutures.wastebot.agent;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.krakenfutures.wastebot.agent.model.IndicatorSnapshot;
//import com.krakenfutures.wastebot.agent.model.MomentumRank;
//import com.krakenfutures.wastebot.agent.model.Signal;
//import com.krakenfutures.wastebot.agent.util.IndicatorCalculator;
//import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.ta4j.core.BarSeries;
//
//import java.util.ArrayList;
//import java.util.List;
//
//public class SpotSignalAgent {
//    private static final Logger log = LoggerFactory.getLogger(SpotSignalAgent.class);
//    private final ObjectMapper objectMapper;
//
//    public SpotSignalAgent(ObjectMapper objectMapper) {
//        this.objectMapper = objectMapper;
//    }
//
//    public Signal evaluate(
//        String asset,
//        List<Candle> candles,
//        double balance,
//        List<MomentumRank> momentumRanks,
//        boolean existingPositionOpen
//    ) {
//        if (candles == null || candles.size() < 22) {
//            return Signal.none("Insufficient candle data", asset);
//        }
//
//        BarSeries series = IndicatorCalculator.buildSeries(asset, candles);
//        int index = series.getEndIndex();
//        IndicatorSnapshot curr = IndicatorCalculator.calculateLatest(series);
//
//        BarSeries prevSubSeries = series.getSubSeries(0, index);
//        IndicatorSnapshot prev = IndicatorCalculator.calculateLatest(prevSubSeries);
//
//        double entryPrice = candles.get(candles.size() - 1).close();
//
//        // Rotation Phase & Position Sizing
//        double perTradeLimit;
//        double maxRiskPct;
//        int maxPositions;
//
//        if (balance <= 1000) {
//            perTradeLimit = balance * 0.10;
//            maxRiskPct = 0.05;
//            maxPositions = 3;
//        } else if (balance <= 5000) {
//            perTradeLimit = balance * 0.20;
//            maxRiskPct = 0.05;
//            maxPositions = 3;
//        } else {
//            perTradeLimit = balance * 0.30;
//            maxRiskPct = 0.07;
//            maxPositions = 2;
//        }
//
//        // Indicators
//        double ema9 = curr.ema9();
//        double ema21 = curr.ema21();
//        double rsi = curr.rsi();
//        double vwap = curr.vwap();
//        double atr = curr.atr();
//        double volumeRatio = curr.volumeRatio();
//
//        // Crossover
//        boolean emaCrossedUp = (prev.ema9() <= prev.ema21()) && (ema9 > ema21);
//        boolean emaCrossedDown = (prev.ema9() >= prev.ema21()) && (ema9 < ema21);
//
//        // Best asset / Momentum Rank
//        boolean isTopMomentum = !momentumRanks.isEmpty() && momentumRanks.get(0).asset().equals(asset);
//
//        String signal = Signal.SIGNAL_NONE;
//        String action = Signal.ACTION_WAIT;
//        int confidence = 0;
//        double sl = 0;
//        double tp1 = 0;
//        double tp2 = 0;
//        double tp3 = 0;
//        double rr = 0;
//        String reason = "Market sideways";
//        boolean slTpVerified = false;
//        boolean safeToTrade = false;
//        String logLine;
//
//        boolean buySignal = emaCrossedUp && (entryPrice > vwap) && (rsi >= 50 && rsi <= 65) && (volumeRatio >= 1.2) && isTopMomentum;
//        boolean sellSignal = existingPositionOpen && (rsi > 72 || emaCrossedDown || (entryPrice < vwap));
//
//        if (buySignal && !existingPositionOpen) {
//            signal = Signal.SIGNAL_BUY;
//            action = Signal.ACTION_ENTER;
//            confidence = 80;
//
//            // Stop Loss -7% max
//            double candidateSl = entryPrice - (atr * 1.5);
//            double maxSlPenalty = entryPrice * (1.0 - maxRiskPct);
//            sl = Math.max(candidateSl, maxSlPenalty);
//
//            tp1 = entryPrice + (atr * 1.5);
//            tp2 = entryPrice + (atr * 3.0);
//            tp3 = entryPrice + (atr * 5.0);
//
//            rr = (tp2 - entryPrice) / (entryPrice - sl);
//            slTpVerified = true;
//            reason = "Bullish crossover + momentum";
//        } else if (sellSignal) {
//            signal = Signal.SIGNAL_SELL;
//            action = Signal.ACTION_EXIT;
//            confidence = 85;
//            reason = rsi > 72 ? "RSI overbought exit" : emaCrossedDown ? "EMA bearish cross exit" : "VWAP breach exit";
//            slTpVerified = true;
//        } else if (existingPositionOpen) {
//            signal = Signal.SIGNAL_HOLD;
//            action = Signal.ACTION_HOLD;
//            confidence = 90;
//            reason = "Keep position";
//            slTpVerified = true;
//        }
//
//        // Check reject rules for BUY
//        boolean reject = false;
//        if (signal.equals(Signal.SIGNAL_BUY)) {
//            if (rsi > 70) {
//                reason = "RSI overbought";
//                reject = true;
//            } else if (volumeRatio < 1.2) {
//                reason = "Low volume confirmation";
//                reject = true;
//            } else if (confidence < 60) {
//                reason = "Low confidence";
//                reject = true;
//            }
//        }
//
//        if (reject) {
//            signal = Signal.SIGNAL_NONE;
//            action = Signal.ACTION_WAIT;
//            slTpVerified = false;
//            safeToTrade = false;
//            logLine = String.format("[SKIP] Asset=%s | Reason=%s | RSI=%.1f", asset, reason, rsi);
//        } else if (signal.equals(Signal.SIGNAL_NONE)) {
//            logLine = String.format("[TICK] 4H | Asset=%s | Price=%.2f | RSI=%.1f | Momentum=%.2f%%",
//                asset, entryPrice, rsi, isTopMomentum ? momentumRanks.get(0).momentum() : 0.0);
//        } else if (signal.equals(Signal.SIGNAL_BUY)) {
//            safeToTrade = true;
//            logLine = String.format("[BUY] %s | Entry=%.2f | SL=%.2f | TP1=%.2f | TP2=%.2f | TP3=%.2f",
//                asset, entryPrice, sl, tp1, tp2, tp3);
//        } else if (signal.equals(Signal.SIGNAL_SELL)) {
//            safeToTrade = true;
//            logLine = String.format("[SELL] %s | Exit=%.2f | Reason=%s | P&L=%.2f%%",
//                asset, entryPrice, reason, ((entryPrice - sl) / sl * 100.0));
//        } else {
//            safeToTrade = true;
//            logLine = String.format("[HOLD] %s | Signal=neutral | Reason=%s", asset, reason);
//        }
//
//        return new Signal(
//            signal,
//            action,
//            confidence,
//            entryPrice,
//            sl,
//            tp1,
//            tp2,
//            tp3,
//            1, // Default 1 contract/position size
//            rr,
//            atr,
//            asset,
//            reason,
//            logLine,
//            curr,
//            slTpVerified,
//            safeToTrade
//        );
//    }
//}
