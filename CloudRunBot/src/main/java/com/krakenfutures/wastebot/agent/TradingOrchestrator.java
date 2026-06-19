package com.krakenfutures.wastebot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krakenfutures.wastebot.agent.client.FuturesOrderWebSocket;
import com.krakenfutures.wastebot.agent.client.MarketDataService;
import com.krakenfutures.wastebot.agent.client.OrderWebSocketService;
import com.krakenfutures.wastebot.agent.model.*;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
import com.krakenfutures.wastebot.agent.util.TradeLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TradingOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(TradingOrchestrator.class);

    private final ObjectMapper objectMapper;
    private final MarketDataService marketDataService;
    private final OrderWebSocketService orderWebSocketService;
    private final FuturesOrderWebSocket futuresOrderWebSocket;

    // The Master Prompts Agents
    private final ChartAnalysisAgent chartAnalysisAgent;
    private final FuturesSignalAgent futuresSignalAgent;
    private final SpotSignalAgent spotSignalAgent;
    private final RiskManagerAgent riskManagerAgent;
    private final PerformanceAnalyzerAgent performanceAnalyzerAgent;

    // Settings & Session State
    private final boolean isSpotBot;
    private final boolean isFuturesBot;
    private double balance;
    private boolean positionOpen = false;
    private double entryPrice = 0.0;
    private double currentSL = 0.0;
    private double currentTP1 = 0.0;
    private double currentTP2 = 0.0;
    private double currentTP3 = 0.0;
    private int positionContracts = 0;
    private long entryTimeMs = 0L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "orch-tick");
        t.setDaemon(true);
        return t;
    });

    public TradingOrchestrator(
        ObjectMapper objectMapper,
        boolean isSpotBot,
        boolean isFuturesBot,
        double initialBalance,
        String apiKey,
        String apiSecret,
        boolean paperMode
    ) {
        this.objectMapper = objectMapper;
        this.isSpotBot = isSpotBot;
        this.isFuturesBot = isFuturesBot;
        this.balance = initialBalance;

        this.marketDataService = new MarketDataService(objectMapper);
        this.orderWebSocketService = new OrderWebSocketService(objectMapper, apiKey, apiSecret, paperMode);
        this.futuresOrderWebSocket = new FuturesOrderWebSocket(objectMapper, apiKey, apiSecret, paperMode);

        this.chartAnalysisAgent = new ChartAnalysisAgent();
        this.futuresSignalAgent = new FuturesSignalAgent(objectMapper);
        this.spotSignalAgent = new SpotSignalAgent(objectMapper);
        this.riskManagerAgent = new RiskManagerAgent(objectMapper);
        this.performanceAnalyzerAgent = new PerformanceAnalyzerAgent();

        // Initialize state
        this.riskManagerAgent.setInitialBalance(initialBalance);
    }

    public void start() {
        log.info("Starting TradingOrchestrator with balance={} | Spot={} | Futures={}", balance, isSpotBot, isFuturesBot);
        if (isFuturesBot) {
            futuresOrderWebSocket.connectAndAuthenticate();
        }

        // Run cycle immediately, then every 15 minutes
        long intervalMin = isFuturesBot ? 15 : 240; // 15m futures / 4H spot
        scheduler.scheduleAtFixedRate(this::runExecutionCycle, 0, intervalMin, TimeUnit.MINUTES);
    }

    public void runExecutionCycle() {
        log.info("[TICK] Beginning Orchestration Cycle. Balance={}", balance);
        try {
            if (isFuturesBot) {
                executeFuturesCycle();
            } else if (isSpotBot) {
                executeSpotCycle();
            }
        } catch (Exception e) {
            TradeLogger.logError("Execution Cycle", e.getMessage());
            log.error("Error in Orchestrator execution cycle: {}", e.getMessage(), e);
        }
    }

    private void executeFuturesCycle() throws Exception {
        String symbol = "MNQ";
        // 1. Fetch recent OHLCV candles
        List<Candle> candles = marketDataService.fetchKrakenCandles(symbol, "15", 30);

        // 2. Generate raw signals using Futures Trading Agent (Prompt 2)
        Signal signal = futuresSignalAgent.evaluate(candles, balance, positionOpen);
        log.info(signal.logLine());

        if (signal.signal().equals(Signal.SIGNAL_NONE)) {
            return;
        }

        // 3. Final safety check with Risk Manager Agent (Prompt 4)
        RiskDecision risk = riskManagerAgent.validate(signal, balance, positionOpen ? 1 : 0);
        log.info(risk.logLine());

        if (!risk.approved()) {
            TradeLogger.logSkip(risk.reason(), signal.indicators().rsi(), signal.confidence());
            return;
        }

        // Apply risk modifications if any
        double finalSl = risk.modifiedSl() != null ? risk.modifiedSl() : signal.stopLoss();
        double finalTp1 = risk.modifiedTp1() != null ? risk.modifiedTp1() : signal.takeProfit1();
        double finalTp2 = risk.modifiedTp2() != null ? risk.modifiedTp2() : signal.takeProfit2();
        int finalContracts = risk.modifiedContracts() != null ? risk.modifiedContracts() : signal.contracts();

        // 4. Place order via Low-Latency Spot or Derivatives WebSocket Client (Prompt 2 spec)
        if (signal.action().equals(Signal.ACTION_ENTER)) {
            log.info("Placing Futures market order via WebSocket client client side...");
            String cliOrdId = "cli_f_" + System.nanoTime();
            var responseFuture = futuresOrderWebSocket.placeMarketOrder(
                "PF_MNQUSD",
                signal.signal().equals(Signal.SIGNAL_LONG) ? "buy" : "sell",
                finalContracts,
                finalSl,
                finalTp2,
                cliOrdId
            );

            var node = responseFuture.get(10, TimeUnit.SECONDS);
            if (node != null && ("placed".equals(node.path("result").asText()) || node.has("event"))) {
                positionOpen = true;
                entryPrice = signal.entryPrice();
                currentSL = finalSl;
                currentTP1 = finalTp1;
                currentTP2 = finalTp2;
                positionContracts = finalContracts;
                entryTimeMs = System.currentTimeMillis();

                TradeLogger.logEnter(
                    signal.signal(), entryPrice, currentSL, currentTP1, currentTP2
                );
            }
        }
    }

    private void executeSpotCycle() throws Exception {
        // Watchlist rotation
        List<String> watchlist = List.of("ETH/EUR", "SOL/EUR", "BTC/EUR");
        List<MomentumRank> ranks = marketDataService.calculateMomentum(watchlist, "240");

        if (ranks.isEmpty()) {
            TradeLogger.logSkip("No assets retrieved from watchlist", 50, 0);
            return;
        }

        // Trade the strongest momentum asset
        String bestAsset = ranks.get(0).asset();
        double momentum = ranks.get(0).momentum();
        TradeLogger.logRotate(bestAsset, momentum, 55.0);

        List<Candle> candles = marketDataService.fetchKrakenCandles(bestAsset, "240", 30);

        // Raw buy/sell signals generated (Prompt 3)
        Signal signal = spotSignalAgent.evaluate(bestAsset, candles, balance, ranks, positionOpen);
        log.info(signal.logLine());

        if (signal.signal().equals(Signal.SIGNAL_NONE)) {
            return;
        }

        // Final safety Check via Risk Manager Agent (Prompt 4)
        RiskDecision risk = riskManagerAgent.validate(signal, balance, positionOpen ? 1 : 0);
        log.info(risk.logLine());

        if (!risk.approved()) {
            TradeLogger.logSkip(risk.reason(), signal.indicators().rsi(), signal.confidence());
            return;
        }

        if (signal.action().equals(Signal.ACTION_ENTER)) {
            String orderId = orderWebSocketService.executeOrder(bestAsset, "buy", 1.0, signal.entryPrice());
            if (orderId != null) {
                positionOpen = true;
                entryPrice = signal.entryPrice();
                currentSL = signal.stopLoss();
                currentTP1 = signal.takeProfit1();
                currentTP2 = signal.takeProfit2();
                currentTP3 = signal.takeProfit3();
                entryTimeMs = System.currentTimeMillis();

                TradeLogger.logEnter("BUY", entryPrice, currentSL, currentTP1, currentTP2);
            }
        } else if (signal.action().equals(Signal.ACTION_EXIT)) {
            String orderId = orderWebSocketService.executeOrder(bestAsset, "sell", 1.0, signal.entryPrice());
            if (orderId != null) {
                double profit = (signal.entryPrice() - entryPrice) * 10.0;
                balance += profit;
                positionOpen = false;

                TradeLogger.logExit(signal.entryPrice(), profit, (int) ((System.currentTimeMillis() - entryTimeMs) / 60000));
                riskManagerAgent.updateSessionState(profit, 1, profit < 0);
            }
        }
    }

    public String runWeeklyPerformanceReview() throws IOException {
        log.info("Running Performance Analyzer Agent...");
        List<String> logs = TradeLogger.getSessionLogs();
        return performanceAnalyzerAgent.analyzeLogs(logs);
    }

    public String runChartAnalysisManual(String instrument, String timeframe) throws IOException {
        log.info("Initiating manual Chart Analysis Agent for {}", instrument);
        List<Candle> candles = marketDataService.fetchKrakenCandles(instrument, "60", 40);
        return chartAnalysisAgent.analyze(instrument, timeframe, candles);
    }

    public void stop() {
        scheduler.shutdown();
        futuresOrderWebSocket.shutdown();
    }
}
