package com.hope.xchangepractice.strategies;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.criteria.pnl.GrossReturnCriterion;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.HighestValueIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;
import org.ta4j.core.indicators.helpers.TransformIndicator;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import com.hope.xchangepractice.TickerLoader;

/**
 * Strategies which compares current price to global extrema over a week.
 */
public class GlobalExtreamaStrategy {

    // We assume that there were at least one position every 5 minutes during the
    // whole
    // week
    private static final int NB_BARS_PER_WEEK = 12 * 24 * 7;

    /**
     * @param series the bar series
     * @return the global extrema strategy
     */
    public static Strategy buildStrategy(BarSeries series) {
        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }

        ClosePriceIndicator closePrices = new ClosePriceIndicator(series);

        // Getting the high price over the past week
        HighPriceIndicator highPrices = new HighPriceIndicator(series);
        HighestValueIndicator weekHighPrice = new HighestValueIndicator(highPrices, NB_BARS_PER_WEEK);
        // Getting the low price over the past week
        LowPriceIndicator lowPrices = new LowPriceIndicator(series);
        LowestValueIndicator weekLowPrice = new LowestValueIndicator(lowPrices, NB_BARS_PER_WEEK);

        // Going long if the close price goes below the low price
        TransformIndicator downWeek = TransformIndicator.multiply(weekLowPrice, 1.004);
        Rule buyingRule = new UnderIndicatorRule(closePrices, downWeek);

        // Going short if the close price goes above the high price
        TransformIndicator upWeek = TransformIndicator.multiply(weekHighPrice, 0.996);
        Rule sellingRule = new OverIndicatorRule(closePrices, upWeek);

        return new BaseStrategy(buyingRule, sellingRule);
    }

    public static void main(String[] args) {

        // Getting the bar series
        BarSeries series = TickerLoader.createBarSeries();
        // Building the trading strategy
        Strategy strategy = buildStrategy(series);

        // Running the strategy
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord tradingRecord = seriesManager.run(strategy);
        System.out.println("Number of positions for the strategy: " + tradingRecord.getPositionCount());

        // Analysis
        System.out.println(
                "Total return for the strategy: " + new GrossReturnCriterion().calculate(series, tradingRecord));
    }
}