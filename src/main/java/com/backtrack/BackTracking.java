package com.backtrack;

import com.hope.xchangepractice.TickerLoader;
import com.hope.xchangepractice.strategies.ADXStrategy;
import com.hope.xchangepractice.strategies.CCICorrectionStrategy;
import com.hope.xchangepractice.strategies.GlobalExtreamaStrategy;
import com.hope.xchangepractice.strategies.MovingMomentumStrategy;
import com.hope.xchangepractice.strategies.RSI2Strategy;
import java.util.Arrays;
import org.ta4j.core.AnalysisCriterion;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BarSeriesManager;
import org.ta4j.core.Strategy;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.criteria.ReturnOverMaxDrawdownCriterion;
import org.ta4j.core.criteria.VersusBuyAndHoldCriterion;
import org.ta4j.core.criteria.WinningPositionsRatioCriterion;
import org.ta4j.core.criteria.pnl.AverageLossCriterion;
import org.ta4j.core.criteria.pnl.GrossReturnCriterion;

public class BackTracking {

    public static void main(String[] args) {
    
        // Getting the bar series
        BarSeries series = TickerLoader.createBarSeries();
    
        // Building the trading strategy
        Strategy movingMomentumStrategy = MovingMomentumStrategy.buildStrategy(series);

        Strategy rsiStrategy  = RSI2Strategy.buildStrategy(series);

        Strategy globalExtremeaStrategy = GlobalExtreamaStrategy.buildStrategy(series);

        Strategy adxStrategy = ADXStrategy.buildStrategy(series);

        Strategy cciCorrectionStrategy = CCICorrectionStrategy.buildStrategy(series);
    
        // Running the strategy
        BarSeriesManager seriesManager = new BarSeriesManager(series);
        TradingRecord movingMomentumRecord = seriesManager.run(movingMomentumStrategy);
        TradingRecord rsitradingRecord = seriesManager.run(movingMomentumStrategy);

        TradingRecord globaltradingRecord = seriesManager.run(movingMomentumStrategy);

        TradingRecord adxtradingRecord = seriesManager.run(movingMomentumStrategy);

        TradingRecord ccitradingRecord = seriesManager.run(movingMomentumStrategy);


        System.out.println("Number of positions for the strategy: " + movingMomentumRecord.getPositionCount());
    
        // Analysis
        System.out.println(
                "Total return for the strategy: " + new GrossReturnCriterion().calculate(series, movingMomentumRecord));
    
        // Getting the winning positions ratio
        AnalysisCriterion winningPositionsRatio = new WinningPositionsRatioCriterion();
        System.out.println("Winning positions ratio: " + winningPositionsRatio.calculate(series, movingMomentumRecord));
        // Getting a risk-reward ratio
        AnalysisCriterion romad = new ReturnOverMaxDrawdownCriterion();
        System.out.println("Return over Max Drawdown: " + romad.calculate(series, movingMomentumRecord));
    
        // Total return of our strategy vs total return of a buy-and-hold strategy
        AnalysisCriterion vsBuyAndHold = new VersusBuyAndHoldCriterion(new GrossReturnCriterion());
        System.out.println("Our return vs buy-and-hold return: " + vsBuyAndHold.calculate(series, movingMomentumRecord));

        AnalysisCriterion averageLoss = new AverageLossCriterion();
        System.out.println("Average Loss: " + averageLoss.calculate(series, movingMomentumRecord));

        AnalysisCriterion criterion = new GrossReturnCriterion();
        criterion.calculate(series, movingMomentumRecord);
        criterion.calculate(series, rsitradingRecord);
        criterion.calculate(series, globaltradingRecord);
        criterion.calculate(series, adxtradingRecord);
        criterion.calculate(series, ccitradingRecord);

        Strategy bestStrategy = criterion.chooseBest(seriesManager, Arrays.asList(movingMomentumStrategy, rsiStrategy, cciCorrectionStrategy, adxStrategy, globalExtremeaStrategy));
        
        System.out.println("Best Strategy " +bestStrategy.getName());




    }

  
}

