package com.hope.xchangepractice.strategies;

import java.io.IOException;
import java.text.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.criteria.pnl.GrossReturnCriterion;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.StopGainRule;
import org.ta4j.core.rules.StopLossRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import com.hope.xchangepractice.TickerLoader;

public class XchangepracticeApplication {

  private static final Logger LOG = LoggerFactory.getLogger(XchangepracticeApplication.class);
  public static void main(String[] args) throws InterruptedException, IOException, ParseException {

    BarSeries series = TickerLoader.createBarSeries();

    strategy(series);

  }

  private static void strategy(BarSeries series) {

    Num firstClosePrice = series.getBar(0).getClosePrice();
    System.out.println("First close price: " + firstClosePrice.doubleValue());
    // Or within an indicator:
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    // Here is the same close price:
    System.out.println(firstClosePrice.isEqual(closePrice.getValue(0)));
    // equal to firstClosePrice

    // Getting the simple moving average (SMA) of the close price over the last 5
    // ticks
    SMAIndicator shortSma = new SMAIndicator(closePrice, 5);
    // Here is the 5-ticks-SMA value at the 42nd index
    System.out.println("5-ticks-SMA value at the 15rd index: " +
        shortSma.getValue(15).doubleValue());

    // Getting a longer SMA (e.g. over the 30 last ticks)
    SMAIndicator longSma = new SMAIndicator(closePrice, 16);

    // Buying rules
    // We want to buy:
    // - if the 5-ticks SMA crosses over 30-ticks SMA
    // - or if the price goes below a defined price (e.g $800.00)
    Rule buyingRule = new CrossedUpIndicatorRule(shortSma, longSma)
        .or(new CrossedDownIndicatorRule(closePrice, 800d));

    // Selling rules
    // We want to sell:
    // - if the 5-ticks SMA crosses under 30-ticks SMA
    // - or if the price loses more than 3%
    // - or if the price earns more than 2%
    Rule sellingRule = new CrossedDownIndicatorRule(shortSma, longSma)
        .or(new StopLossRule(closePrice, 3.0))
        .or(new StopGainRule(closePrice, 2.0));

    Strategy strategy = new BaseStrategy(buyingRule, sellingRule);

    // Running our juicy trading strategy...
    BarSeriesManager manager = new BarSeriesManager(series);
    TradingRecord tradingRecord = manager.run(strategy);
    System.out.println("Number of trades for our strategy: " +
        tradingRecord.getPositionCount());

        System.out.println(
          "Total return for the strategy: " + new GrossReturnCriterion().calculate(series, tradingRecord));

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

    // Entry rule
    // The long-term trend is up when a security is above its 200-period SMA.
    Rule entryRule = new OverIndicatorRule(shortSma, longSma) // Trend
        .and(new CrossedDownIndicatorRule(rsi, 5)) // Signal 1
        .and(new OverIndicatorRule(shortSma, closePrice)); // Signal 2

    // Exit rule
    // The long-term trend is down when a security is below its 200-period SMA.
    Rule exitRule = new UnderIndicatorRule(shortSma, longSma) // Trend
        .and(new CrossedUpIndicatorRule(rsi, 95)) // Signal 1
        .and(new UnderIndicatorRule(shortSma, closePrice)); // Signal 2

    // TODO: Finalize the strategy

    return new BaseStrategy(entryRule, exitRule);
  }
}
