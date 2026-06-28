package cloudcode.krakenfutures.leadlag.trade;

import cloudcode.krakenfutures.leadlag.signal.SignalType;

public class Position {

    public enum Side { LONG, SHORT }

    private final String symbol;
    private final Side side;
    private final double entryPrice;
    private final double quantity;
    private final long entryTimestamp;
    private double currentPrice;

    public Position(String symbol, Side side, double entryPrice, double quantity, long entryTimestamp) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryTimestamp = entryTimestamp;
        this.currentPrice = entryPrice;
    }

    public String getSymbol() { return symbol; }
    public Side getSide() { return side; }
    public double getEntryPrice() { return entryPrice; }
    public double getQuantity() { return quantity; }
    public long getEntryTimestamp() { return entryTimestamp; }
    public double getCurrentPrice() { return currentPrice; }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    /**
     * Returns the current unrealized P&L as a percentage of the entry price.
     * For LONG: (current - entry) / entry * 100
     * For SHORT: (entry - current) / entry * 100
     */
    public double getUnrealizedPnlPct() {
        if (entryPrice == 0) return 0;
        if (side == Side.LONG) {
            return (currentPrice - entryPrice) / entryPrice * 100.0;
        } else {
            return (entryPrice - currentPrice) / entryPrice * 100.0;
        }
    }

    public double getUnrealizedPnlEur() {
        return getUnrealizedPnlPct() / 100.0 * entryPrice * quantity;
    }

    /**
     * Returns true if the position direction matches the BTC signal direction.
     * LONG matches BULLISH, SHORT matches BEARISH.
     */
    public boolean matchesSignal(SignalType signalType) {
        if (signalType == SignalType.BULLISH && side == Side.LONG) return true;
        if (signalType == SignalType.BEARISH && side == Side.SHORT) return true;
        return false;
    }

    @Override
    public String toString() {
        return String.format("Position{%s %s qty=%.6f entry=%.4f current=%.4f pnl=%.2f%%}",
                side, symbol, quantity, entryPrice, currentPrice, getUnrealizedPnlPct());
    }
}