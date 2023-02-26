package ta4jexamples.bots;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseStrategy;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.Strategy;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.OverIndicatorRule;
import org.ta4j.core.rules.UnderIndicatorRule;

import com.api.SubmitClient;
import com.hope.xchangepractice.TickerLoader;
import com.hope.xchangepractice.strategies.MovingMomentumStrategy;

/**
 * This class is an example of a dummy trading bot using ta4j.
 * <p/>
 */
public class TradingBotOnMovingBarSeries {

    /**
     * Close price of the last bar
     */
    private static Num LAST_BAR_CLOSE_PRICE;

    public static void main(String[] args) throws InterruptedException, IOException, KeyManagementException,
            InvalidKeyException, NoSuchAlgorithmException {

        initateRun();
    }


     /**
     * Builds a moving bar series (i.e. keeping only the maxBarCount last bars)
     *
     * @param maxBarCount the number of bars to keep in the bar series (at maximum)
     * @return a moving bar series
     */
    private static BarSeries initMovingBarSeries(int maxBarCount) throws IOException {
        BarSeries series = TickerLoader.createBarSeries();
        System.out.print("Initial bar count: " + series.getBarCount());
        // Limitating the number of bars to maxBarCount
        series.setMaximumBarCount(maxBarCount);
        LAST_BAR_CLOSE_PRICE = series.getBar(series.getEndIndex()).getClosePrice();
        System.out.println(" (limited to " + maxBarCount + "), close price = " + LAST_BAR_CLOSE_PRICE);
        return series;
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
                BarSeries liveSeries = TickerLoader.liveMarketData();
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
