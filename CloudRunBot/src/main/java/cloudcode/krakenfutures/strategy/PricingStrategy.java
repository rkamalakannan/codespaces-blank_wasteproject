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
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class PricingStrategy {

    public static final double LTC_AMOUNT = 5;
    public static final double BTC_AMOUNT = 0.1;
    public static final double ETH_AMOUNT = 1;
    public static final double BCH_AMOUNT = 5;
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
                BaseBar bar = new BaseBar(Duration.ofMinutes(5), ZonedDateTime.ofInstant(Instant.ofEpochSecond(krakenOHLC.getTime()), ZoneId.systemDefault()), krakenOHLC.getMyopen(), krakenOHLC.getHigh(), krakenOHLC.getLow(), krakenOHLC.getClose(), String.valueOf(krakenOHLC.getVolume()));
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
//        backTesting(instrument, originalAmount, series);


        // Building the trading strategy
        buildStrategy(series, instrument, originalAmount);
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

        Indicator<Num> sr = new StochasticRSIIndicator(rsiIndicator, 14);
        Indicator<Num> k = new SMAIndicator(sr, 3);
        Indicator<Num> d = new SMAIndicator(k, 3);

        StochasticOscillatorKIndicator stochasticOscillK = new StochasticOscillatorKIndicator(series, 14);



        System.out.println("Series Index for instrument : " + instrument.getBase() + " " + stochasticOscillK.getValue(series.getEndIndex()));

        Rule accumulationSrEntryRule = new CrossedUpIndicatorRule(stochasticOscillK, 20);
        Rule accumulationSrExitRule = new CrossedDownIndicatorRule(stochasticOscillK, 80);

        Rule srEntry = new CrossedUpIndicatorRule(k, d);
        Rule srExit = new CrossedDownIndicatorRule(k, d);
        // Entry rule
        // A buy signal is generated when the ‘Supertrend’ closes above the price
        //and a sell signal is generated when it closes below the closing price.
        Rule modifiedEntryRule = new CrossedUpIndicatorRule(rocIndicator, 0).or(new CrossedUpIndicatorRule(rsiIndicator, 31));
        Rule modifiedExitRule = new CrossedDownIndicatorRule(rocIndicator, 0).or(new CrossedDownIndicatorRule(rsiIndicator, 65));

        Rule stEntryRule = new CrossedUpIndicatorRule(superTrendIndicator, closePrice);//.or(new IsRisingRule(superTrendLowIndicator, 2)); //a > b
        Rule stExitRule = new CrossedDownIndicatorRule(superTrendIndicator, closePrice);//.or(new IsFallingRule(superTrendUpIndicator, 2)); //a < b


        Rule rocEntryRule = new CrossedUpIndicatorRule(rsiIndicator, rocIndicator);
        Rule rocExitRule = new CrossedDownIndicatorRule(rsiIndicator, rocIndicator);


        Rule entryRule = new CrossedUpIndicatorRule(closePrice, superTrendIndicator);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, superTrendIndicator);


        Rule macdEntryRule = new CrossedUpIndicatorRule(macd, emaMacd);
        Rule macdExitRule = new CrossedDownIndicatorRule(macd, emaMacd);


        System.out.println("Entry Rule Satisfied:" + " for instrument " + instrument.getBase() + " " + accumulationSrEntryRule.isSatisfied(series.getEndIndex()));
        System.out.println("Exit Rule Satisfied:" + " for instrument " + instrument.getBase() + " " + accumulationSrExitRule.isSatisfied(series.getEndIndex()));
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

        krakenFutureConfiguration.placeOrder(instrument, originalAmount, accumulationSrEntryRule, accumulationSrExitRule, series);

        // Exit rule
        // The long-term trend is down when a security is below its 200-period SMA.
        // Rule exitRule = new UnderIndicatorRule(shortSma, longSma) // Trend
        // .and(new CrossedUpIndicatorRule(rsi, 70)) // Signal 1
        // .and(new UnderIndicatorRule(shortSma, closePrice)); // Signal 2

//        BarSeriesManager seriesManager = new BarSeriesManager(series);
//
//        BaseStrategy superTrendStrategy = new BaseStrategy(entryRule, exitRule);
//        TradingRecord superTrendRecord = seriesManager.run(superTrendStrategy);
//        BaseStrategy macdStrategy = new BaseStrategy(macdEntryRule, macdExitRule);
//        TradingRecord macdRecord = seriesManager.run(macdStrategy);
//        BaseStrategy modifiedStrategy = new BaseStrategy(modifiedEntryRule, modifiedExitRule);
//        TradingRecord modifiedRecord = seriesManager.run(modifiedStrategy);
//        BaseStrategy rocStrategy = new BaseStrategy(rocEntryRule, rocExitRule);
//        TradingRecord rocRecord = seriesManager.run(rocStrategy);
//        BaseStrategy stochStrategy = new BaseStrategy(stEntryRule, stExitRule);
//        TradingRecord stochRecord = seriesManager.run(stochStrategy);
//
//        AnalysisCriterion criterion = new ReturnOverMaxDrawdownCriterion();
//        Strategy bestStrategy = criterion.chooseBest(seriesManager, Arrays.asList(superTrendStrategy, macdStrategy,
//                modifiedStrategy, rocStrategy, stochStrategy));
//
//        System.out.println("criterion.calculate(series, superTrendRecord) = " + criterion.calculate(series, superTrendRecord)); // Returns the result for strategy1
//        System.out.println("criterion.calculate(series, macdRecord) = " + criterion.calculate(series, macdRecord));
//        System.out.println("criterion.calculate(series, modifiedRecord) = " + criterion.calculate(series, modifiedRecord));
//        System.out.println("criterion.calculate(series, rocRecord) = " + criterion.calculate(series, rocRecord));
//        System.out.println("criterion.calculate(series, stochRecord) = " + criterion.calculate(series, stochRecord));


        return new BaseStrategy(accumulationSrEntryRule, accumulationSrExitRule);
    }

    public void stopLossTrailing(Instrument instrument) {
        BarSeries series = createBarSeries(instrument);
        DecimalNum tslPercentage = DecimalNum.valueOf(10.00);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        TrailingStopLossRule rule = new TrailingStopLossRule(closePrice, tslPercentage);


    }

    @Scheduled(cron = "*/5 * * * * *")
    public void execution() {
        Instrument instrument = new CurrencyPair("BCH", "USD");//set currency pair here
        BigDecimal originalAmount = BigDecimal.valueOf(BCH_AMOUNT); // set volume here
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try {
//                    Thread.sleep(Duration.ofSeconds(20).toMillis());
                placeOrder(instrument, originalAmount);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, myExecutor);

    }

    @Scheduled(cron = "*/5 * * * * *")
    public void ltcExecution() {
        Instrument instrument = new CurrencyPair("LTC", "USD");//set currency pair here
        BigDecimal originalAmount = BigDecimal.valueOf(LTC_AMOUNT); // set volume here
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try {
//                    Thread.sleep(Duration.ofSeconds(20).toMillis());
                placeOrder(instrument, originalAmount);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, myExecutor);

    }

    @Scheduled(cron = "*/5 * * * * *")
    public void btcExecution() {
        Instrument instrument = new CurrencyPair("BTC", "USD");//set currency pair here
        BigDecimal originalAmount = BigDecimal.valueOf(BTC_AMOUNT); // set volume here
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try {
//                    Thread.sleep(Duration.ofSeconds(20).toMillis());
                placeOrder(instrument, originalAmount);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, myExecutor);

    }


    @Scheduled(cron = "*/5 * * * * *")
    public void ethExecution() {
        Instrument instrument = new CurrencyPair("ETH", "USD");//set currency pair here
        BigDecimal originalAmount = BigDecimal.valueOf(ETH_AMOUNT); // set volume here
        CompletableFuture<Void> run = CompletableFuture.runAsync(() -> {
            try {
//                    Thread.sleep(Duration.ofSeconds(20).toMillis());
                placeOrder(instrument, originalAmount);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, myExecutor);

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