package com.hope.xchangepractice;

import java.io.IOException;
import java.sql.Date;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.CandleStickData;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.kraken.Kraken;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.KrakenUtils;
import org.knowm.xchange.kraken.dto.marketdata.KrakenAssetPairs;
import org.knowm.xchange.kraken.dto.marketdata.KrakenAssets;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.knowm.xchange.service.marketdata.params.Params;
import org.knowm.xchange.service.trade.params.CandleStickDataParams;
import org.knowm.xchange.service.trade.params.DefaultCandleStickParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.servlet.mvc.condition.ParamsRequestCondition;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.StopGainRule;
import org.ta4j.core.rules.StopLossRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingExchangeFactory;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import info.bitrich.xchangestream.kraken.KrakenStreamingExchange;
import info.bitrich.xchangestream.kraken.KrakenStreamingMarketDataService;
import io.reactivex.disposables.Disposable;

public class XchangepracticeApplication {

  private static final Logger LOG = LoggerFactory.getLogger(XchangepracticeApplication.class);
  static BarSeries series = new BaseBarSeriesBuilder().withNumTypeOf(DoubleNum::valueOf).withMaxBarCount(100).build(); // explicit

  public static void main(String[] args) throws InterruptedException, IOException, ParseException {

    createBarSeries();

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

  }

  public static BarSeries createBarSeries() {
    try {
      Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
      KrakenMarketDataService marketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
      LocalDateTime time = LocalDateTime.now().minusHours(8);
      ZoneId zoneId = ZoneId.systemDefault();
      long epoch = time.atZone(zoneId).toEpochSecond();
      KrakenOHLCs krakenOHLCs = marketDataService.getKrakenOHLC(new CurrencyPair("ATOM", "USD"), 30, epoch);
      for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
        BaseBar bar = new BaseBar(
            Duration.ofMinutes(30),
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()),
                ZoneId.systemDefault()),
            krakenOHLC.getOpen().doubleValue(),
            krakenOHLC.getHigh().doubleValue(),
            krakenOHLC.getLow().doubleValue(),
            krakenOHLC.getClose().doubleValue(),
            krakenOHLC.getVolume().doubleValue());
        series.addBar(bar);

      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return series;
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
