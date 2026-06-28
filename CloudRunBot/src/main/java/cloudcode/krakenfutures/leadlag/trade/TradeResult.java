package cloudcode.krakenfutures.leadlag.trade;

public class TradeResult {

    private final String symbol;
    private final Position.Side side;
    private final double entryPrice;
    private final double exitPrice;
    private final double quantity;
    private final double grossPnlPct;
    private final double grossPnlEur;
    private final double feeCostEur;
    private final double netPnlPct;
    private final double netPnlEur;
    private final ExitReason exitReason;
    private final long entryTimestamp;
    private final long exitTimestamp;
    private final boolean paper;

    public TradeResult(String symbol, Position.Side side, double entryPrice, double exitPrice,
                       double quantity, double grossPnlPct, double grossPnlEur,
                       double feeCostEur, ExitReason exitReason,
                       long entryTimestamp, long exitTimestamp, boolean paper) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.quantity = quantity;
        this.grossPnlPct = grossPnlPct;
        this.grossPnlEur = grossPnlEur;
        this.feeCostEur = feeCostEur;
        this.netPnlPct = grossPnlPct - (feeCostEur / (entryPrice * quantity) * 100.0);
        this.netPnlEur = grossPnlEur - feeCostEur;
        this.exitReason = exitReason;
        this.entryTimestamp = entryTimestamp;
        this.exitTimestamp = exitTimestamp;
        this.paper = paper;
    }

    public String getSymbol() { return symbol; }
    public Position.Side getSide() { return side; }
    public double getEntryPrice() { return entryPrice; }
    public double getExitPrice() { return exitPrice; }
    public double getQuantity() { return quantity; }
    public double getGrossPnlPct() { return grossPnlPct; }
    public double getGrossPnlEur() { return grossPnlEur; }
    public double getFeeCostEur() { return feeCostEur; }
    public double getNetPnlPct() { return netPnlPct; }
    public double getNetPnlEur() { return netPnlEur; }
    public ExitReason getExitReason() { return exitReason; }
    public long getEntryTimestamp() { return entryTimestamp; }
    public long getExitTimestamp() { return exitTimestamp; }
    public boolean isPaper() { return paper; }

    @Override
    public String toString() {
        return String.format("TradeResult{%s %s qty=%.6f entry=%.4f exit=%.4f grossPnl=%.2f%% (%.2f EUR) fee=%.2f EUR netPnl=%.2f%% (%.2f EUR) reason=%s mode=%s}",
                side, symbol, quantity, entryPrice, exitPrice,
                grossPnlPct, grossPnlEur, feeCostEur, netPnlPct, netPnlEur,
                exitReason, paper ? "PAPER" : "LIVE");
    }
}