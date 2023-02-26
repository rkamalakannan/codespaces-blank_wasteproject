package ta4jexamples.bots;

import java.time.*;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.marketdata.Ticker;
import org.knowm.xchange.kraken.KrakenExchange;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLC;
import org.knowm.xchange.kraken.dto.marketdata.KrakenOHLCs;
import org.knowm.xchange.kraken.service.KrakenMarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.ta4j.core.*;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import com.api.SubmitClient;
import com.hope.xchangepractice.strategies.MovingMomentumStrategy;

/**
 * This class is an example of a dummy trading bot using ta4j.
 * <p/>
 */
@SpringBootApplication
public class TradingBotOnMovingBarSeries {

    /**
     * Close price of the last bar
     */
    private static Num LAST_BAR_CLOSE_PRICE;

    public static void main(String[] args) throws InterruptedException, IOException, KeyManagementException,
            InvalidKeyException, NoSuchAlgorithmException {

        SpringApplication.run(TradingBotOnMovingBarSeries.class, args);
        initateRun();
    }


     /**
     * Builds a moving bar series (i.e. keeping only the maxBarCount last bars)
     *
     * @param maxBarCount the number of bars to keep in the bar series (at maximum)
     * @return a moving bar series
     */
    private static BarSeries initMovingBarSeries(int maxBarCount) throws IOException {
        BarSeries series = createBarSeries();
        System.out.print("Initial bar count: " + series.getBarCount());
        // Limitating the number of bars to maxBarCount
        series.setMaximumBarCount(maxBarCount);
        LAST_BAR_CLOSE_PRICE = series.getBar(series.getEndIndex()).getClosePrice();
        System.out.println(" (limited to " + maxBarCount + "), close price = " + LAST_BAR_CLOSE_PRICE);
        return series;
    }

    public static BarSeries createBarSeries() {

        BarSeries series = new BaseBarSeriesBuilder()
                .withMaxBarCount(Integer.MAX_VALUE).build();
        try {
            KrakenExchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
            KrakenMarketDataService krakenMarketDataService = (KrakenMarketDataService) krakenExchange.getMarketDataService();
            LocalDateTime time = LocalDateTime.now().minusWeeks(1);
            ZoneId zoneId = ZoneId.systemDefault();
            long epoch = time.atZone(zoneId).toEpochSecond();
            KrakenOHLCs krakenOHLCs = krakenMarketDataService.getKrakenOHLC(new CurrencyPair("BTC", "USD"), 15, epoch);
            Ticker ticker = krakenExchange.getMarketDataService().getTicker(new CurrencyPair("BTC", "USD"));
//            .getKr(new CurrencyPair("BTC", "USD"), 15, epoch);
            System.out.println("Inside KrakenOHLCLoader" + ticker.toString());
            System.out.println("Inside OHLC" + ticker);
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

    public static BarSeries liveMarketData() throws IOException {
        BarSeries liveSeries = new BaseBarSeriesBuilder().build();
        KrakenMarketDataService marketDataService = marketData();
        Ticker ticker = marketDataService.getTicker(CurrencyPair.BTC_USD);

        BaseBar baseBar =  new BaseBar(Duration.ofSeconds(1), ZonedDateTime.now(), ticker.getOpen(), ticker.getHigh(),
                ticker.getLow(), ticker.getLast(),
                ticker.getVolume());

        liveSeries.addBar(baseBar);

        return liveSeries;


    }

    private static KrakenMarketDataService marketData() {
        Exchange krakenExchange = ExchangeFactory.INSTANCE.createExchange(KrakenExchange.class);
        return (KrakenMarketDataService) krakenExchange.getMarketDataService();
    }
    
    public static void initateRun() throws InterruptedException, IOException, KeyManagementException,
    InvalidKeyException, NoSuchAlgorithmException {
        

        while (true) {
            System.out.println("********************** Initialization **********************");
            // Getting the bar series
            BarSeries series = initMovingBarSeries(20);

            // Building the trading strategy
            Strategy strategy = MovingMomentumStrategy.buildStrategy(series);

            // Initializing the trading history
            TradingRecord tradingRecord = new BaseTradingRecord();
            System.out.println("************************************************************");

            /*
             * We run the strategy for the 50 next bars.
             */
            for (int i = 0; i < 50; i++) {

                // New bar
                Thread.sleep(30); // I know...
                BarSeries liveSeries = liveMarketData();
                Bar newBar = liveSeries.getFirstBar();
                System.out.println("------------------------------------------------------\n" + "Bar " + i
                        + " added, close price = " + newBar.getClosePrice().doubleValue());
                series.addBar(liveSeries.getFirstBar());
                SubmitClient.sendOrder();

                int endIndex = series.getEndIndex();
                if (strategy.shouldEnter(endIndex)) {
                    // Our strategy should enter
                    System.out.println("Strategy should ENTER on " + endIndex);
                    boolean entered = tradingRecord.enter(endIndex, newBar.getClosePrice(), DecimalNum.valueOf(10));
                    if (entered) {
                        Trade entry = tradingRecord.getLastEntry();
                        System.out.println(
                                "Entered on " + entry.getIndex() + " (price=" + entry.getNetPrice().doubleValue()
                                        + ", amount=" + entry.getAmount().doubleValue() + ")");
                        SubmitClient.sendOrder();

                    }
                } else if (strategy.shouldExit(endIndex)) {
                    // Our strategy should exit
                    System.out.println("Strategy should EXIT on " + endIndex);
                    boolean exited = tradingRecord.exit(endIndex, newBar.getClosePrice(), DecimalNum.valueOf(10));
                    if (exited) {
                        Trade exit = tradingRecord.getLastExit();
                        System.out
                                .println("Exited on " + exit.getIndex() + " (price=" + exit.getNetPrice().doubleValue()
                                        + ", amount=" + exit.getAmount().doubleValue() + ")");
                        SubmitClient.sendOrder();

                    }
                }
            }
        }
    }

}
