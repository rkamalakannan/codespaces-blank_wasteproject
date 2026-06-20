package com.krakenfutures.bot.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive backtest result containing performance metrics for strategy comparison.
 */
public class BacktestResult {
    private final String strategyName;
    private final List<Double> equityCurve;
    private final List<Trade> trades;
    private final double totalReturn;
    private final double winRate;
    private final double profitFactor;
    private final double maxDrawdown;
    private final int totalTrades;
    private final int winningTrades;
    private final int losingTrades;
    private final double avgWin;
    private final double avgLoss;
    private final double sharpeRatio;
    private final long backtestDurationMs;

    public BacktestResult(String strategyName, List<Double> equityCurve, List<Trade> trades,
                          long backtestDurationMs) {
        this.strategyName = strategyName;
        this.equityCurve = new ArrayList<>(equityCurve);
        this.trades = new ArrayList<>(trades);
        this.backtestDurationMs = backtestDurationMs;
        
        // Calculate metrics
        this.totalTrades = trades.size();
        this.winningTrades = (int) trades.stream().filter(t -> t.getProfit() > 0).count();
        this.losingTrades = totalTrades - winningTrades;
        this.winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;
        
        double totalWins = trades.stream().filter(t -> t.getProfit() > 0).mapToDouble(Trade::getProfit).sum();
        double totalLosses = Math.abs(trades.stream().filter(t -> t.getProfit() < 0).mapToDouble(Trade::getProfit).sum());
        this.profitFactor = totalLosses > 0 ? totalWins / totalLosses : totalWins > 0 ? Double.POSITIVE_INFINITY : 0;
        
        this.avgWin = winningTrades > 0 ? totalWins / winningTrades : 0;
        this.avgLoss = losingTrades > 0 ? totalLosses / losingTrades : 0;
        
        // Calculate total return
        double initialEquity = equityCurve.isEmpty() ? 10000 : equityCurve.get(0);
        double finalEquity = equityCurve.isEmpty() ? 10000 : equityCurve.get(equityCurve.size() - 1);
        this.totalReturn = ((finalEquity - initialEquity) / initialEquity) * 100;
        
        // Calculate max drawdown
        this.maxDrawdown = calculateMaxDrawdown(equityCurve);
        
        // Calculate Sharpe ratio (simplified)
        this.sharpeRatio = calculateSharpeRatio(trades);
    }

    private double calculateMaxDrawdown(List<Double> equityCurve) {
        if (equityCurve.isEmpty()) return 0;
        
        double maxEquity = equityCurve.get(0);
        double maxDrawdown = 0;
        
        for (double equity : equityCurve) {
            if (equity > maxEquity) {
                maxEquity = equity;
            }
            double drawdown = (maxEquity - equity) / maxEquity * 100;
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }

    private double calculateSharpeRatio(List<Trade> trades) {
        if (trades.size() < 2) return 0;
    
        List<Double> returns = new ArrayList<>();
        for (int i = 1; i < trades.size(); i++) {
            double previousProfit = trades.get(i - 1).getProfit();
            if (previousProfit == 0) {
                continue;
            }

            double ret = (trades.get(i).getProfit() - previousProfit) /
                         Math.abs(previousProfit);
            if (!Double.isNaN(ret) && !Double.isInfinite(ret)) {
                returns.add(ret);
            }
        }
    
        if (returns.isEmpty()) return 0;
    
        double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double stdDev = Math.sqrt(returns.stream()
                .mapToDouble(r -> Math.pow(r - avgReturn, 2))
                .average()
                .orElse(0));
    
        return stdDev > 0 ? (avgReturn / stdDev) * Math.sqrt(252) : 0;
    }

    public String getStrategyName() { return strategyName; }
    public List<Double> getEquityCurve() { return equityCurve; }
    public List<Trade> getTrades() { return trades; }
    public double getTotalReturn() { return totalReturn; }
    public double getWinRate() { return winRate; }
    public double getProfitFactor() { return profitFactor; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public int getTotalTrades() { return totalTrades; }
    public int getWinningTrades() { return winningTrades; }
    public int getLosingTrades() { return losingTrades; }
    public double getAvgWin() { return avgWin; }
    public double getAvgLoss() { return avgLoss; }
    public double getSharpeRatio() { return sharpeRatio; }
    public long getBacktestDurationMs() { return backtestDurationMs; }

    @Override
    public String toString() {
        return String.format(
            "=== %s ===\n" +
            "Total Return: %.2f%% | Win Rate: %.2f%% | Profit Factor: %.2f\n" +
            "Max Drawdown: %.2f%% | Sharpe Ratio: %.2f\n" +
            "Total Trades: %d (Winners: %d, Losers: %d)\n" +
            "Avg Win: $%.2f | Avg Loss: $%.2f",
            strategyName, totalReturn, winRate, profitFactor,
            maxDrawdown, sharpeRatio, totalTrades, winningTrades, losingTrades,
            avgWin, avgLoss
        );
    }

    public static class Trade {
        private final int index;
        private final String type;
        private final double entryPrice;
        private final double exitPrice;
        private final double profit;
        private final int barsHeld;

        public Trade(int index, String type, double entryPrice, double exitPrice, double profit, int barsHeld) {
            this.index = index;
            this.type = type;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.profit = profit;
            this.barsHeld = barsHeld;
        }

        public int getIndex() { return index; }
        public String getType() { return type; }
        public double getEntryPrice() { return entryPrice; }
        public double getExitPrice() { return exitPrice; }
        public double getProfit() { return profit; }
        public int getBarsHeld() { return barsHeld; }
    }
}