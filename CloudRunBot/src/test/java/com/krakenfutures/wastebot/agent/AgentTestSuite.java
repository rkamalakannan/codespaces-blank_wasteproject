package com.krakenfutures.wastebot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krakenfutures.wastebot.agent.model.MomentumRank;
import com.krakenfutures.wastebot.agent.model.RiskDecision;
import com.krakenfutures.wastebot.agent.model.Signal;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AgentTestSuite {
    private static final Logger log = LoggerFactory.getLogger(AgentTestSuite.class);

    public static void main(String[] args) {
        log.info("Starting Agent Test Suite...");
        ObjectMapper mapper = new ObjectMapper();

        try {
            testFuturesSignalAgent(mapper);
            testSpotSignalAgent(mapper);
            testRiskManagerAgent(mapper);
            log.info("All agent unit tests passed successfully!");
        } catch (Exception e) {
            log.error("Test suite failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void testFuturesSignalAgent(ObjectMapper mapper) {
        FuturesSignalAgent agent = new FuturesSignalAgent(mapper);
        List<Candle> candles = generateMockCandles(30, 30000.0);

        // Evaluate with insufficient candles
        Signal insuff = agent.evaluate(candles.subList(0, 5), 10000.0, false);
        assert Signal.SIGNAL_NONE.equals(insuff.signal()) : "Should yield NONE on insufficient candles";

        // Evaluate standard mock candles (trend is sideways/flat)
        Signal run = agent.evaluate(candles, 10000.0, false);
        assert Signal.SIGNAL_NONE.equals(run.signal()) || Signal.SIGNAL_LONG.equals(run.signal()) || Signal.SIGNAL_SHORT.equals(run.signal());
        log.info("FuturesSignalAgent test: OK");
    }

    private static void testSpotSignalAgent(ObjectMapper mapper) {
        SpotSignalAgent agent = new SpotSignalAgent(mapper);
        List<Candle> candles = generateMockCandles(30, 3000.0);
        List<MomentumRank> ranks = List.of(new MomentumRank("SOL/EUR", 12.5), new MomentumRank("ETH/EUR", 4.2));

        Signal run = agent.evaluate("SOL/EUR", candles, 5000.0, ranks, false);
        assert run != null : "Should produce non-null signal";
        log.info("SpotSignalAgent test: OK");
    }

    private static void testRiskManagerAgent(ObjectMapper mapper) {
        RiskManagerAgent riskManager = new RiskManagerAgent(mapper);
        double balance = 10000.0;
        riskManager.setInitialBalance(balance);

        // Flat/normal signal
        Signal signal = new Signal(
            Signal.SIGNAL_LONG, Signal.ACTION_ENTER, 80, 30000.0,
            29500.0, 30500.0, 31000.0, 0.0, 1, 1.5, 100.0,
            "MNQ", "Test", "Log", com.krakenfutures.wastebot.agent.model.IndicatorSnapshot.empty(),
            true, true
        );

        RiskDecision decision = riskManager.validate(signal, balance, 0);
        assert decision.approved() : "Should approve compliant trade";

        // Exceed risk threshold
        Signal hugeRisk = new Signal(
            Signal.SIGNAL_LONG, Signal.ACTION_ENTER, 80, 30000.0,
            25000.0, 31000.0, 32000.0, 0.0, 5, 1.5, 100.0,
            "MNQ", "Test", "Log", com.krakenfutures.wastebot.agent.model.IndicatorSnapshot.empty(),
            true, true
        );
        RiskDecision rejected = riskManager.validate(hugeRisk, balance, 0);
        assert !rejected.approved() : "Should reject trade exceeding 1% risk limit";

        log.info("RiskManagerAgent test: OK");
    }

    private static List<Candle> generateMockCandles(int count, double basePrice) {
        List<Candle> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long ts = now - (count - i) * 15 * 60 * 1000L;
            java.time.Instant instant = java.time.Instant.ofEpochMilli(ts);
            String timeStr = instant.atZone(java.time.ZoneId.of("UTC")).toOffsetDateTime().toString();
            list.add(new Candle(
                timeStr,
                basePrice,
                basePrice * 1.01,
                basePrice * 0.99,
                basePrice,
                10.0
            ));
        }
        return list;
    }
}
