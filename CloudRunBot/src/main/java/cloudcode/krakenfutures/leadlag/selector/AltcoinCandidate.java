package cloudcode.krakenfutures.leadlag.selector;

public class AltcoinCandidate {

    private final String symbol;
    private final double correlation;
    private final double liquidityScore;
    private final double lagBonus;
    private final double spreadPct;
    private final double totalScore;

    public AltcoinCandidate(String symbol, double correlation, double liquidityScore,
                            double lagBonus, double spreadPct, double totalScore) {
        this.symbol = symbol;
        this.correlation = correlation;
        this.liquidityScore = liquidityScore;
        this.lagBonus = lagBonus;
        this.spreadPct = spreadPct;
        this.totalScore = totalScore;
    }

    public String getSymbol() { return symbol; }
    public double getCorrelation() { return correlation; }
    public double getLiquidityScore() { return liquidityScore; }
    public double getLagBonus() { return lagBonus; }
    public double getSpreadPct() { return spreadPct; }
    public double getTotalScore() { return totalScore; }

    @Override
    public String toString() {
        return String.format("AltcoinCandidate{%s corr=%.4f liq=%.4f lag=%.4f spread=%.4f%% score=%.4f}",
                symbol, correlation, liquidityScore, lagBonus, spreadPct, totalScore);
    }
}