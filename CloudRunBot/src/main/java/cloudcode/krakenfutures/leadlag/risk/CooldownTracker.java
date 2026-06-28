package cloudcode.krakenfutures.leadlag.risk;

import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;

public class CooldownTracker {

    private final LeadLagConfig config;
    private volatile long lastTradeExitTime = 0;

    public CooldownTracker(LeadLagConfig config) {
        this.config = config;
    }

    /**
     * Records the current time as the last trade exit time.
     * Should be called whenever a trade is closed.
     */
    public synchronized void recordExit() {
        this.lastTradeExitTime = System.currentTimeMillis();
    }

    /**
     * Returns true if the bot is currently in cooldown and cannot open new trades.
     */
    public synchronized boolean isInCooldown() {
        if (lastTradeExitTime == 0) return false;
        long elapsed = System.currentTimeMillis() - lastTradeExitTime;
        return elapsed < config.getCooldownSec() * 1000L;
    }

    /**
     * Returns the remaining cooldown in milliseconds, or 0 if not in cooldown.
     */
    public synchronized long getRemainingCooldownMs() {
        if (lastTradeExitTime == 0) return 0;
        long elapsed = System.currentTimeMillis() - lastTradeExitTime;
        long cooldownMs = config.getCooldownSec() * 1000L;
        return Math.max(0, cooldownMs - elapsed);
    }

    public synchronized void reset() {
        this.lastTradeExitTime = 0;
    }
}