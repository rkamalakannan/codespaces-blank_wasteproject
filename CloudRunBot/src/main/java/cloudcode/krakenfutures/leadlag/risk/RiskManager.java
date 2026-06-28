package cloudcode.krakenfutures.leadlag.risk;

import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.leadlag.trade.PositionManager;

public class RiskManager {

    private static final double MIN_TOP_OF_BOOK_USD = 5000.0;
    private static final double MAX_SPREAD_PCT = 0.3;
    private static final int MAX_CONCURRENT_TRADES = 1;

    private final LeadLagConfig config;
    private final PositionManager positionManager;
    private final CooldownTracker cooldownTracker;
    private volatile boolean halted = false;
    private double dailyDrawdown = 0.0;
    private static final double MAX_DAILY_DRAWDOWN_PCT = 3.0;

    public RiskManager(LeadLagConfig config, PositionManager positionManager, CooldownTracker cooldownTracker) {
        this.config = config;
        this.positionManager = positionManager;
        this.cooldownTracker = cooldownTracker;
    }

    /**
     * Returns true if a new trade is allowed under all risk rules.
     */
    public synchronized boolean canOpenNewTrade(double altcoinSpreadPct, double topOfBookUsd) {
        if (halted) return false;
        if (positionManager.hasOpenPosition()) return false; // max 1 concurrent trade
        if (cooldownTracker.isInCooldown()) return false;
        if (altcoinSpreadPct >= MAX_SPREAD_PCT) return false; // spread protection
        if (topOfBookUsd < MIN_TOP_OF_BOOK_USD) return false; // liquidity filter
        if (dailyDrawdown >= MAX_DAILY_DRAWDOWN_PCT) return false; // daily drawdown limit
        return true;
    }

    /**
     * Records the P&L of a closed trade to track daily drawdown.
     */
    public synchronized void recordTradePnl(double pnlEur, double startingBalanceEur) {
        if (startingBalanceEur > 0 && pnlEur < 0) {
            dailyDrawdown += Math.abs(pnlEur) / startingBalanceEur * 100.0;
            if (dailyDrawdown >= MAX_DAILY_DRAWDOWN_PCT) {
                halted = true;
            }
        } else if (pnlEur > 0) {
            // Profitable trades offset the drawdown (capped at 0)
            dailyDrawdown = Math.max(0, dailyDrawdown - pnlEur / startingBalanceEur * 100.0);
        }
    }

    public synchronized void resetDailyDrawdown() {
        dailyDrawdown = 0.0;
        halted = false;
    }

    public synchronized boolean isHalted() {
        return halted;
    }
}