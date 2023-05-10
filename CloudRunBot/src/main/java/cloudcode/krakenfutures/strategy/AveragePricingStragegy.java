package cloudcode.krakenfutures.strategy;

import cloudcode.krakenfutures.weblayer.KrakenFutureConfiguration;
import cloudcode.krakenfutures.weblayer.KrakenSpotConfiguration;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.AnalysisCriterion.PositionFilter;
import org.ta4j.core.criteria.*;
import org.ta4j.core.criteria.pnl.ProfitCriterion;
import org.ta4j.core.criteria.pnl.ProfitLossCriterion;
import org.ta4j.core.criteria.pnl.ReturnCriterion;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class AveragePricingStragegy {

    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;

    @Autowired
    KrakenFutureConfiguration krakenFutureConfiguration;

    public KrakenOHLCs getOhlc5m(Instrument instrument) throws IOException {

        return krakenSpotConfiguration.getKrakenOHLCs(instrument);

    }

    public BarSeries createBarSeries(Instrument instrument) {

        BarSeries series = new BaseBarSeriesBuilder()
                .withMaxBarCount(Integer.MAX_VALUE).build();

        try {
            KrakenOHLCs krakenOHLCs = getOhlc5m(instrument);
            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
                BaseBar bar = new BaseBar(
                        Duration.ofMinutes(1),
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()),
                                ZoneId.systemDefault()),
                        krakenOHLC.getOpen(),
                        krakenOHLC.getHigh(),
                        krakenOHLC.getLow(),
                        krakenOHLC.getClose(),
                        krakenOHLC.getVolume());
                series.addBar(bar);

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return series;
    }

    // public double findAveragePrice(Instrument instrument, BigDecimal
    // originalAmount) throws IOException {
    // BarSeries series = createBarSeries(instrument);
    // BarSeries lastBarSeries = new
    // BaseBarSeriesBuilder().withName("lastBarSeries").build();
    // lastBarSeries.addBar(series.getLastBar());
    // System.out.println("LastBarSeriers"+lastBarSeries.getBarData().toString());
    // TypicalPriceIndicator typicalPriceIndicator = new
    // TypicalPriceIndicator(lastBarSeries);
    // RSIIndicator rsiIndicator = new RSIIndicator(typicalPriceIndicator, 30);
    // System.out.println(rsiIndicator.getValue(0).doubleValue());
    // backTesting(instrument);
    // return typicalPriceIndicator.getValue(0).doubleValue();
    // }

    public void placeOrder(Instrument instrument, BigDecimal originalAmount) throws IOException {
        BarSeries series = createBarSeries(instrument);
//        backTesting(instrument, originalAmount, series);

        // Building the trading strategy
        buildStrategy(series, instrument, originalAmount);
    }

    public Strategy buildStrategy(BarSeries series, Instrument instrument, BigDecimal originalAmount)
            throws IOException {
        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        MACDIndicator macd = new MACDIndicator(closePrice);

        EMAIndicator emaMacd = new EMAIndicator(macd, 9);


        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, 14);


        SuperTrendIndicator superTrendIndicator = new SuperTrendIndicator(series);

        Indicator<Num> superTrendLowIndicator = superTrendIndicator.getSuperTrendLowerBandIndicator();
        Indicator<Num> superTrendUpIndicator = superTrendIndicator.getSuperTrendUpperBandIndicator();

        System.out.println("Up" + superTrendUpIndicator.getValue(series.getEndIndex()));
        System.out.println("Low" + superTrendLowIndicator.getValue(series.getEndIndex()));

        System.out.println("SuperTrendIndicator" + superTrendIndicator.getValue(series.getEndIndex()));

        System.out.println("close" + closePrice.getValue(series.getEndIndex()));

        // Entry rule
        // A buy signal is generated when the ‘Supertrend’ closes above the price 
        //and a sell signal is generated when it closes below the closing price.
        Rule entryRule = new OverIndicatorRule(superTrendIndicator, closePrice);//.or(new IsRisingRule(superTrendLowIndicator, 2)); //a > b
        Rule exitRule = new UnderIndicatorRule(superTrendIndicator, closePrice);//.or(new IsFallingRule(superTrendUpIndicator, 2)); //a < b


        Rule macdEntryRule = new CrossedUpIndicatorRule(macd, emaMacd);
        Rule macdExitRule = new CrossedDownIndicatorRule(macd, emaMacd);

        System.out.println("Entry Rule Satisfied:" + macdEntryRule.isSatisfied(series.getEndIndex()));
        System.out.println("Exit Rule Satisified:" + macdExitRule.isSatisfied(series.getEndIndex()));

        krakenFutureConfiguration.placeOrder(instrument, originalAmount, macdEntryRule, macdExitRule, series);

        // Exit rule
        // The long-term trend is down when a security is below its 200-period SMA.
        // Rule exitRule = new UnderIndicatorRule(shortSma, longSma) // Trend
        // .and(new CrossedUpIndicatorRule(rsi, 70)) // Signal 1
        // .and(new UnderIndicatorRule(shortSma, closePrice)); // Signal 2

        return new BaseStrategy(entryRule, exitRule);
    }

    public void backTesting(Instrument instrument, BigDecimal originalAmount, BarSeries series) throws IOException {
        // Building the trading strategy
        Strategy strategy = buildStrategy(series, instrument, originalAmount);


        // Running the strategy
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord tradingRecord = seriesManager.run(strategy);
        System.out.println("Number of positions for the strategy: " + tradingRecord.getPositionCount());

        System.out.println("Position List:" + tradingRecord.getPositions().toString());
        // Analysis
        // System.out.println("Total return for the strategy: " + new
        // ReturnCriterion().calculate(series, tradingRecord));

        ReturnOverMaxDrawdownCriterion totalReturn = new ReturnOverMaxDrawdownCriterion();
        System.out.println("Total return: " + totalReturn.calculate(series, tradingRecord));
        // Number of bars
        System.out.println("Number of bars: " + new NumberOfBarsCriterion().calculate(series, tradingRecord));
        // Average profit (per bar)
        System.out.println(
                "Average return (per bar): " + new AverageReturnPerBarCriterion().calculate(series, tradingRecord));
        // Number of positions
        System.out.println("Number of positions: " + new NumberOfPositionsCriterion().calculate(series, tradingRecord));
        // Profitable position ratio
        // System.out.println("Winning positions ratio: "
        // + new PositionsRatioCriterion(PositionFilter.PROFIT).calculate(series,
        // tradingRecord));
        // Maximum drawdown
        System.out.println("Maximum drawdown: " + new MaximumDrawdownCriterion().calculate(series, tradingRecord));
        // Reward-risk ratio
        System.out.println("Return over maximum drawdown: "
                + new ReturnOverMaxDrawdownCriterion().calculate(series, tradingRecord));
        // Total transaction cost
        System.out.println("Total transaction cost (from $1000): "
                + new LinearTransactionCostCriterion(1000, 0.005).calculate(series, tradingRecord));
        // Buy-and-hold
        System.out
                .println("Buy-and-hold return: " + new EnterAndHoldReturnCriterion().calculate(series, tradingRecord));
        // Total profit vs buy-and-hold
        // System.out.println("Custom strategy return vs buy-and-hold strategy return: "

        // + new VersusEnterAndHoldCriterion(totalReturn).calculate(series,
        // tradingRecord));

        System.out.println("Total Profit: " + new ProfitCriterion().calculate(series, tradingRecord));

        System.out.println("Total Profit Loss: " + new ProfitLossCriterion().calculate(series, tradingRecord));

        System.out.println(
                "Winning positionn: " + new NumberOfWinningPositionsCriterion().calculate(series, tradingRecord));
        // Getting the winning positions ratio
        AnalysisCriterion winningPositionsRatio = new PositionsRatioCriterion(PositionFilter.PROFIT);
        System.out.println("Winning positions ratio: " + winningPositionsRatio.calculate(series, tradingRecord));
        // Getting a risk-reward ratio
        AnalysisCriterion romad = new ReturnOverMaxDrawdownCriterion();
        System.out.println("Return over Max Drawdown: " + romad.calculate(series, tradingRecord));

        // Total return of our strategy vs total return of a buy-and-hold strategy
        AnalysisCriterion vsBuyAndHold = new VersusEnterAndHoldCriterion(new ReturnCriterion());
        System.out.println("Our return vs buy-and-hold return: " + vsBuyAndHold.calculate(series, tradingRecord));
    }

}