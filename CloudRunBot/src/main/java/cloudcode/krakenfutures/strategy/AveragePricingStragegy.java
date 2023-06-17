package cloudcode.krakenfutures.strategy;

import cloudcode.krakenfutures.models.Candle;
import cloudcode.krakenfutures.models.Root;
import cloudcode.krakenfutures.weblayer.KrakenFutureConfiguration;
import cloudcode.krakenfutures.weblayer.KrakenSpotConfiguration;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingExchangeFactory;
import info.bitrich.xchangestream.krakenfutures.KrakenFuturesStreamingExchange;
import io.reactivex.disposables.Disposable;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.derivative.FuturesContract;
import org.knowm.xchange.instrument.Instrument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.ta4j.core.*;
import org.ta4j.core.AnalysisCriterion.PositionFilter;
import org.ta4j.core.criteria.*;
import org.ta4j.core.criteria.pnl.ProfitCriterion;
import org.ta4j.core.criteria.pnl.ProfitLossCriterion;
import org.ta4j.core.criteria.pnl.ReturnCriterion;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.ROCIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.rules.CrossedDownIndicatorRule;
import org.ta4j.core.rules.CrossedUpIndicatorRule;
import org.ta4j.core.rules.TrailingStopLossRule;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AveragePricingStragegy {

    @Autowired
    KrakenSpotConfiguration krakenSpotConfiguration;

    @Autowired
    KrakenFutureConfiguration krakenFutureConfiguration;

    ExecutorService myExecutor = Executors.newFixedThreadPool(2);


    public Root getOhlc5m(Instrument instrument) throws IOException {

        return krakenFutureConfiguration.getKrakenFuturesOHLCs(instrument);

    }

    public void liveSeries(Instrument instrument, BigDecimal originalAmount) {

        ExchangeSpecification spec = new ExchangeSpecification(KrakenFuturesStreamingExchange.class);
//        spec.setApiKey("9uJBCOFWib8xnSfGIUnK5WyoHkdvJ/n0soLSgcAKilKNmF289B4A3myC");
//        spec.setSecretKey("MOzKv4yBmJIOIyJJBLQFoanaHLYSssMRizOL4M8Kwg7UcPaFH9RCl26a8ViyE+JkR9iZXpf9GQ+mnnTWKZERiXBZ");
        spec.setApiKey("hp/R4xE5vE38ZZZ1vmgpX/ii5QX5VIQTVd97WS4d/zSAE2FizzaUCQMz");
        spec.setSecretKey("UAofzx1foXy+2xqNbt50Q4cPFy4Jllp+gyVlK6rzy6suWNirtPfy3VDVo4fdt5omRaPTn8J6V76uYoVy1sWbtC+u");
        spec.setExchangeSpecificParametersItem(Exchange.USE_SANDBOX, true);
        StreamingExchange exchange = StreamingExchangeFactory.INSTANCE.createExchange(spec);
        exchange.connect().blockingAwait();
        instrument = new FuturesContract(instrument.getBase() + "/USD/PERP");
        BarSeries series = new BaseBarSeriesBuilder().withMaxBarCount(200).build();
        Instrument finalInstrument = instrument;
        series.addBar(Duration.ofSeconds(1), ZonedDateTime.now(),
                0, 0, 0, 0, 0);
        Disposable subscription1 = exchange.getStreamingMarketDataService()
                .getTicker(instrument)
                .subscribe(
                        trade -> {
                            System.out.println("trade = " + trade);
                            ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant(trade.getTimestamp().toInstant(),
                                    ZoneId.systemDefault());
                            if (zonedDateTime.isAfter(series.getLastBar().getEndTime())) {
                                series.addBar(Duration.ofSeconds(1), zonedDateTime,
                                        trade.getLast(), trade.getLast(), trade.getLast(), trade.getLast(), trade.getVolume());
                                buildStrategy(series, finalInstrument, originalAmount);
                            }
                        });


    }

//    public BarSeries createBarSeries(Instrument instrument) {
//
//        BarSeries series = new BaseBarSeriesBuilder().withMaxBarCount(200).build();
//        try {
//            KrakenOHLCs krakenOHLCs = getOhlc5m(instrument);
//            for (KrakenOHLC krakenOHLC : krakenOHLCs.getOHLCs()) {
//                BaseBar bar = new BaseBar(Duration.ofMinutes(1), ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()), ZoneId.systemDefault()), krakenOHLC.getOpen(), krakenOHLC.getHigh(), krakenOHLC.getLow(), krakenOHLC.getClose(), krakenOHLC.getVolume());
//                series.addBar(bar);
//
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//        return series;
//    }

    public BarSeries createBarSeries(Instrument instrument) {

        BarSeries series = new BaseBarSeriesBuilder().withMaxBarCount(Integer.MAX_VALUE).build();
        try {
            Root krakenOHLCs = getOhlc5m(instrument);
            for (Candle krakenOHLC : krakenOHLCs.getCandles()) {
                BaseBar bar = new BaseBar(Duration.ofMinutes(1), ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()), ZoneId.systemDefault()), krakenOHLC.getMyopen(), krakenOHLC.getHigh(), krakenOHLC.getLow(), krakenOHLC.getClose(), String.valueOf(krakenOHLC.getVolume()));
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
        final BarSeries series = createBarSeries(instrument);
        backTesting(instrument, originalAmount, series);


        // Building the trading strategy
//        buildStrategy(series, instrument, originalAmount);
    }

    public Strategy buildStrategy(BarSeries series, Instrument instrument, BigDecimal originalAmount) throws IOException {

        if (series == null) {
            throw new IllegalArgumentException("Series cannot be null");
        }
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        MACDIndicator macd = new MACDIndicator(closePrice);
        EMAIndicator emaMacd = new EMAIndicator(macd, 9);
        RSIIndicator rsiIndicator = new RSIIndicator(closePrice, 14);
        ROCIndicator rocIndicator = new ROCIndicator(closePrice, 9);
        SuperTrendIndicator superTrendIndicator = new SuperTrendIndicator(series);
        System.out.println(rocIndicator.getValue(series.getEndIndex()));
        System.out.println(rsiIndicator.getValue(series.getEndIndex()));
        System.out.println("closePrice = " + closePrice.getValue(series.getEndIndex()));
        System.out.println("superTrendIndicator = " + superTrendIndicator.getValue(series.getEndIndex()));


        // Entry rule
        // A buy signal is generated when the ‘Supertrend’ closes above the price 
        //and a sell signal is generated when it closes below the closing price.
//        Rule entryRule = new CrossedUpIndicatorRule(rocIndicator, 0).or(new CrossedUpIndicatorRule(rsiIndicator, 31));
//        Rule exitRule = new CrossedDownIndicatorRule(rocIndicator, 0).or(new CrossedDownIndicatorRule(rsiIndicator, 55));

//        Rule entryRule = new CrossedUpIndicatorRule(superTrendIndicator, closePrice);//.or(new IsRisingRule(superTrendLowIndicator, 2)); //a > b
//        Rule exitRule = new CrossedDownIndicatorRule(superTrendIndicator, closePrice);//.or(new IsFallingRule(superTrendUpIndicator, 2)); //a < b


        Rule entryRule = new CrossedUpIndicatorRule(rocIndicator, rsiIndicator);
        Rule exitRule = new CrossedDownIndicatorRule(rocIndicator, rsiIndicator);

//        Rule entryRule = new CrossedUpIndicatorRule(superTrendIndicator, closePrice);
//        Rule exitRule = new CrossedDownIndicatorRule(superTrendIndicator, closePrice);


        Rule macdEntryRule = new CrossedUpIndicatorRule(macd, emaMacd);
        Rule macdExitRule = new CrossedDownIndicatorRule(macd, emaMacd);


        System.out.println("Entry Rule Satisfied:" + entryRule.isSatisfied(series.getEndIndex()));
        System.out.println("Exit Rule Satisified:" + exitRule.isSatisfied(series.getEndIndex()));
        System.setProperty("java.awt.headless", "false");

//        TacChartBuilder.of(barseries, Theme.DARK)//        TacChartBuilder.of(barseries)
//                .withIndicator(
//                        IndicatorConfiguration.Builder.of(rocIndicator)
//                                .name("Short Ema")
//                                .plotType(PlotType.OVERLAY)
//                                .chartType(ChartType.LINE)
//                                .color(Color.BLUE))
//                .withIndicator(IndicatorConfiguration.Builder.of(rocIndicator)
//                        .name("Short Ema")
//                        .plotType(PlotType.OVERLAY)
//                        .chartType(ChartType.LINE)
//                        .color(Color.RED))
//                .buildAndShow();

//        krakenFutureConfiguration.placeOrder(instrument, originalAmount, entryRule, exitRule, series);

        // Exit rule
        // The long-term trend is down when a security is below its 200-period SMA.
        // Rule exitRule = new UnderIndicatorRule(shortSma, longSma) // Trend
        // .and(new CrossedUpIndicatorRule(rsi, 70)) // Signal 1
        // .and(new UnderIndicatorRule(shortSma, closePrice)); // Signal 2

        return new BaseStrategy(entryRule, exitRule);
    }

    public void stopLossTrailing(Instrument instrument) {
        BarSeries series = createBarSeries(instrument);
        DecimalNum tslPercentage = DecimalNum.valueOf(10.00);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        TrailingStopLossRule rule = new TrailingStopLossRule(closePrice, tslPercentage);


    }

    @Scheduled(cron = "*/20 * * * * *")
    public void execution() throws ExecutionException, InterruptedException {
        Instrument instrument = new CurrencyPair("BCH", "USD");//set currency pair here
        BigDecimal originalAmount = BigDecimal.valueOf(100); // set volume here
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try {
//                    Thread.sleep(Duration.ofSeconds(20).toMillis());
                placeOrder(instrument, originalAmount);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });


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
        System.out.println("Average return (per bar): " + new AverageReturnPerBarCriterion().calculate(series, tradingRecord));
        // Number of positions
        System.out.println("Number of positions: " + new NumberOfPositionsCriterion().calculate(series, tradingRecord));
        // Profitable position ratio
        // System.out.println("Winning positions ratio: "
        // + new PositionsRatioCriterion(PositionFilter.PROFIT).calculate(series,
        // tradingRecord));
        // Maximum drawdown
        System.out.println("Maximum drawdown: " + new MaximumDrawdownCriterion().calculate(series, tradingRecord));
        // Reward-risk ratio
        System.out.println("Return over maximum drawdown: " + new ReturnOverMaxDrawdownCriterion().calculate(series, tradingRecord));
        // Total transaction cost
        System.out.println("Total transaction cost (from $1000): " + new LinearTransactionCostCriterion(1000, 0.005).calculate(series, tradingRecord));
        // Buy-and-hold
        System.out.println("Buy-and-hold return: " + new EnterAndHoldReturnCriterion().calculate(series, tradingRecord));
        // Total profit vs buy-and-hold
        // System.out.println("Custom strategy return vs buy-and-hold strategy return: "

        // + new VersusEnterAndHoldCriterion(totalReturn).calculate(series,
        // tradingRecord));

        System.out.println("Total Profit: " + new ProfitCriterion().calculate(series, tradingRecord));

        System.out.println("Total Profit Loss: " + new ProfitLossCriterion().calculate(series, tradingRecord));

        System.out.println("Winning positionn: " + new NumberOfWinningPositionsCriterion().calculate(series, tradingRecord));
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