package cloudcode.krakenfutures.strategy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;


import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.TypicalPriceIndicator;
import org.ta4j.core.AnalysisCriterion.PositionFilter;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.criteria.AverageReturnPerBarCriterion;
import org.ta4j.core.criteria.EnterAndHoldReturnCriterion;
import org.ta4j.core.criteria.LinearTransactionCostCriterion;
import org.ta4j.core.criteria.MaximumDrawdownCriterion;
import org.ta4j.core.criteria.NumberOfBarsCriterion;
import org.ta4j.core.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.criteria.NumberOfWinningPositionsCriterion;
import org.ta4j.core.criteria.ReturnOverMaxDrawdownCriterion;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import cloudcode.krakenfutures.weblayer.KrakenSpotConfiguration;

@Component
public class AveragePricingStragegy {


    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;
    
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
                        Duration.ofMinutes(15),
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

    public double findAveragePrice(Instrument instrument, BigDecimal originalAmount) {
        BarSeries series = createBarSeries(instrument);
        BarSeries lastBarSeries = new BaseBarSeriesBuilder().withName("lastBarSeries").build();
        lastBarSeries.addBar(series.getLastBar());
        System.out.println("LastBarSeriers"+lastBarSeries.getBarData().toString());
        TypicalPriceIndicator typicalPriceIndicator = new TypicalPriceIndicator(lastBarSeries);
        RSIIndicator rsiIndicator = new RSIIndicator(typicalPriceIndicator, 30);
        System.out.println(rsiIndicator.getValue(0).doubleValue());
        backTesting(instrument);
        return typicalPriceIndicator.getValue(0).doubleValue();
    }

    public static Strategy buildStrategy(BarSeries series) {
        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSma = new SMAIndicator(closePrice, 5);
        SMAIndicator longSma = new SMAIndicator(closePrice, 200);

        // We use a 2-period RSI indicator to identify buying
        // or selling opportunities within the bigger trend.
        RSIIndicator rsi = new RSIIndicator(closePrice, 2);


        TypicalPriceIndicator typicalPriceIndicator = new TypicalPriceIndicator(series);


        // Entry rule
        // The long-term trend is up when a security is above its 200-period SMA.
        Rule entryRule = new OverIndicatorRule(shortSma, longSma) // Trend
                .and(new CrossedDownIndicatorRule(rsi, 30)) // Signal 1
                .and(new OverIndicatorRule(shortSma, closePrice)); // Signal 2

        // Exit rule
        // The long-term trend is down when a security is below its 200-period SMA.
        Rule exitRule = new UnderIndicatorRule(shortSma, longSma) // Trend
                .and(new CrossedUpIndicatorRule(rsi, 70)) // Signal 1
                .and(new UnderIndicatorRule(shortSma, closePrice)); // Signal 2

        return new BaseStrategy(entryRule, exitRule);
    }

    public void backTesting(Instrument instrument) {
        BarSeries series = createBarSeries(instrument);

        // Building the trading strategy
        Strategy strategy = buildStrategy(series);

        // Running the strategy
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord tradingRecord = seriesManager.run(strategy);
        System.out.println("Number of positions for the strategy: " + tradingRecord.getPositionCount());

        System.out.println("Position List:" + tradingRecord.getPositions().toString());
        // Analysis
        // System.out.println("Total return for the strategy: " + new ReturnCriterion().calculate(series, tradingRecord));

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
                // + new PositionsRatioCriterion(PositionFilter.PROFIT).calculate(series, tradingRecord));
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

        // + new VersusEnterAndHoldCriterion(totalReturn).calculate(series, tradingRecord));

        System.out.println("Winning positionn: " + new NumberOfWinningPositionsCriterion().calculate(series, tradingRecord));

    }

}
