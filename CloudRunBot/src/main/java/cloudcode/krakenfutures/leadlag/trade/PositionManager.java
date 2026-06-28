package cloudcode.krakenfutures.leadlag.trade;

import java.util.function.Consumer;

import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.leadlag.signal.SignalType;

public class PositionManager {

    private final LeadLagConfig config;
    private final TradeExecutor tradeExecutor;
    private volatile Position activePosition;

    public PositionManager(LeadLagConfig config, TradeExecutor tradeExecutor) {
        this.config = config;
        this.tradeExecutor = tradeExecutor;
    }

    public synchronized void openPosition(Position position) {
        this.activePosition = position;
    }

    public synchronized boolean hasOpenPosition() {
        return activePosition != null;
    }

    public synchronized Position getActivePosition() {
        return activePosition;
    }

    /**
     * Monitors the active position against TP/SL/timeout and BTC signal reversal.
     * TP/SL thresholds are compared against NET PnL (after Kraken fees).
     * Should be called on every altcoin price update.
     * 
     * @param currentAltcoinPrice latest altcoin price.
     * @param latestBtcSignal current BTC signal type (for reversal detection).
     * @param onExit callback when the position is closed.
     */
    public synchronized void onPriceUpdate(double currentAltcoinPrice,
                                          SignalType latestBtcSignal,
                                          Consumer<TradeResult> onExit) {
        if (activePosition == null) return;

        activePosition.setCurrentPrice(currentAltcoinPrice);
        double grossPnlPct = activePosition.getUnrealizedPnlPct();

        // Compute net PnL after Kraken round-trip fees (spot entry + spot exit)
        double roundTripFeePct = config.getSpotRoundTripFeePct();
        double netPnlPct = grossPnlPct - roundTripFeePct;

        // Take profit — trigger when NET PnL >= profit target
        if (netPnlPct >= config.getProfitTargetPct()) {
            TradeResult result = tradeExecutor.closePosition(activePosition, currentAltcoinPrice, ExitReason.TAKE_PROFIT);
            activePosition = null;
            onExit.accept(result);
            return;
        }

        // Stop loss — trigger when NET PnL <= stop loss
        if (netPnlPct <= config.getStopLossPct()) {
            TradeResult result = tradeExecutor.closePosition(activePosition, currentAltcoinPrice, ExitReason.STOP_LOSS);
            activePosition = null;
            onExit.accept(result);
            return;
        }

        // Timeout
        long elapsedSec = (System.currentTimeMillis() - activePosition.getEntryTimestamp()) / 1000;
        if (elapsedSec >= config.getMaxHoldTimeSec()) {
            TradeResult result = tradeExecutor.closePosition(activePosition, currentAltcoinPrice, ExitReason.TIMEOUT);
            activePosition = null;
            onExit.accept(result);
            return;
        }

        // BTC signal reversal
        if (latestBtcSignal != null && !activePosition.matchesSignal(latestBtcSignal)) {
            TradeResult result = tradeExecutor.closePosition(activePosition, currentAltcoinPrice, ExitReason.REVERSAL);
            activePosition = null;
            onExit.accept(result);
            return;
        }
    }

    public synchronized void forceClose(Consumer<TradeResult> onExit) {
        if (activePosition == null) return;
        TradeResult result = tradeExecutor.closePosition(activePosition, activePosition.getCurrentPrice(), ExitReason.MANUAL);
        activePosition = null;
        onExit.accept(result);
    }
}