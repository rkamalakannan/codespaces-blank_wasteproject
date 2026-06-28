package cloudcode.krakenfutures;

import cloudcode.krakenfutures.config.TradingConfig;
import cloudcode.krakenfutures.leadlag.LeadLagOrchestrator;
import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.rest.KrakenRestClient;
import cloudcode.krakenfutures.strategy.CrossCurrencyArbitrageStrategy;
import cloudcode.krakenfutures.websocket.FuturesTickerService;
import cloudcode.krakenfutures.websocket.KrakenWebSocketClient;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kraken Cross-Currency Arbitrage Trading Bot.
 *
 * Lightweight, zero-framework Java application optimized for private VPS hosting.
 * Supports both Spot and Futures markets with paper/live trading modes.
 *
 * All credentials come from environment variables — never pass keys as CLI args.
 *
 * Environment variables:
 *   KRAKEN_API_KEY        — Spot API key
 *   KRAKEN_API_SECRET     — Spot API secret
 *   KRAKEN_FUTURES_KEY    — Derivatives API key
 *   KRAKEN_FUTURES_SECRET — Derivatives API secret
 *   TRADING_MODE          — "paper" (default) or "live"
 *   ENABLE_SPOT           — "true" (default) or "false"
 *   ENABLE_FUTURES        — "true" or "false" (default)
 *   TRADE_SIZE_USD        — base trade size in USD equivalent (default 50)
 *   MIN_PROFIT_PCT        — minimum net profit % threshold (default 0.10)
 *   MAX_TICKER_AGE_MS     — max ticker staleness in ms (default 3000)
 *   TRADE_COOLDOWN_MS     — per-asset cooldown in ms (default 30000)
 *
 * JVM tuning for low-latency VPS:
 *   java -server -XX:+UseZGC -XX:+AlwaysPreTouch -Xms256m -Xmx512m \
 *        -jar kraken-trading-bot-1.0.0.jar
 */
public class CloudRunBotApplication {

    private static final Logger log = LoggerFactory.getLogger(CloudRunBotApplication.class);

    public static void main(String[] args) {
        log.info("=== Kraken Trading Bot Starting ===");

        // 1. Load configuration from environment variables
        TradingConfig config = TradingConfig.fromEnvironment();
        ObjectMapper objectMapper = new ObjectMapper();

        // 2. REST client — discover supported pairs dynamically
        KrakenRestClient restClient = new KrakenRestClient(objectMapper, config);

        // 3. Order WebSocket client (supports paper trading)
        KrakenWebSocketClient orderClient = new KrakenWebSocketClient(objectMapper, config);

        // Components to track for shutdown
        SpotTickerWebSocketService spotTickerService = null;
        CrossCurrencyArbitrageStrategy spotStrategy = null;
//        SpotFuturesArbitrageStrategy spotFuturesStrategy = null;
        FuturesTickerService futuresTickerService = null;
        LeadLagOrchestrator leadLagOrchestrator = null;

        // ===== SPOT MARKET =====
        if (config.isSpotEnabled()) {
            log.info("--- Initializing Spot Market ---");

            Map<String, Set<String>> fiatPairs = restClient.fetchFiatSpotPairs();
            List<String> stablecoinPairs = restClient.fetchStablecoinFiatPairs();

            if (fiatPairs.isEmpty()) {
                log.error("No tradable spot fiat pairs found. Check network connectivity to api.kraken.com");
            } else {
                log.info("Found {} assets with multi-fiat pairs, {} stablecoin FX pairs",
                        fiatPairs.size(), stablecoinPairs.size());

                // Connect order WebSocket (only if live trading or for monitoring)
                if (!config.isPaperTrading()) {
                    String wsToken = restClient.getWebSocketToken();
                    orderClient.connect(wsToken);
                } else {
                    log.info("[PAPER] Skipping order WebSocket connection — paper trading mode");
                }

                // Ticker WebSocket — subscribe only to discovered pairs
                spotTickerService = new SpotTickerWebSocketService(objectMapper);
                spotTickerService.buildSubscriptionPairs(fiatPairs, stablecoinPairs);
                spotTickerService.start();

                // Arbitrage strategy — event-driven, orders via Kraken WebSocket client only
                spotStrategy = new CrossCurrencyArbitrageStrategy(spotTickerService, orderClient, config);
                spotStrategy.start();

                log.info("Spot: monitoring {} assets across fiat currencies", fiatPairs.size());
            }
        } else {
            log.info("Spot market disabled (ENABLE_SPOT=false)");
        }

        // ===== LEAD-LAG BOT =====
        if (config.isLeadLagEnabled() && spotTickerService != null) {
            log.info("--- Initializing Lead-Lag Bot ---");

            List<String> discoveredAltcoins = List.of();
            Map<String, Set<String>> fiatPairs = restClient.fetchFiatSpotPairs();
            if (fiatPairs != null && !fiatPairs.isEmpty()) {
                discoveredAltcoins = fiatPairs.keySet().stream()
                        .filter(asset -> !"BTC".equals(asset) && !"XBT".equals(asset))
                        .toList();
            }
            
            LeadLagConfig leadLagConfig;
            leadLagConfig = new LeadLagConfig(discoveredAltcoins);
            leadLagOrchestrator = new LeadLagOrchestrator(leadLagConfig, config, spotTickerService, orderClient);
            leadLagOrchestrator.start();
            log.info("Lead-Lag bot running on {} dynamically discovered altcoins: {}",
                    leadLagConfig.getApprovedAltcoins().size(),
                    leadLagConfig.getApprovedAltcoins());
        } else {
            log.info("Lead-Lag bot disabled (ENABLE_LEADLAG=false or no spot ticker available)");
        }

        // ===== FUTURES MARKET =====
//        if (config.isFuturesEnabled()) {
//            log.info("--- Initializing Futures Market ---");
//
//            List<String> futuresInstruments = restClient.fetchFuturesInstruments();
//
//            if (futuresInstruments.isEmpty()) {
//                log.warn("No tradable futures instruments found.");
//            } else {
//                log.info("Found {} tradeable futures instruments", futuresInstruments.size());
//
//                // Futures ticker WebSocket
//                futuresTickerService = new FuturesTickerService(objectMapper, config);
//                futuresTickerService.setSubscriptionProducts(futuresInstruments);
//                futuresTickerService.start();
//
//                // Log futures ticker updates (strategy hooks can be added here)
//                final FuturesTickerService ftService = futuresTickerService;
//                futuresTickerService.addTickListener(productId -> {
//                    var ticker = ftService.getTicker(productId);
//                    if (ticker != null) {
//                        log.debug("Futures tick: {}", ticker);
//                    }
//                });
//
//                log.info("Futures: streaming {} instruments", futuresInstruments.size());
//
//               // Spot-Futures basis strategy (requires both enabled)
//               if (config.isSpotEnabled() && spotTickerService != null) {
//                   log.info("SPOT_FUTURES_STRATEGY_INIT orderClient={}; restClient={}; futuresOrderWebSocket={}",
//                           orderClient != null, restClient != null, false);
//                   spotFuturesStrategy = new SpotFuturesArbitrageStrategy(spotTickerService, futuresTickerService, restClient, config);
//                   spotFuturesStrategy.start();
//               }
//            }
//        } else {
//            log.info("Futures market disabled (ENABLE_FUTURES=false)");
//        }

        log.info("=== Kraken Trading Bot Running ({}) ===",
                config.isPaperTrading() ? "PAPER MODE" : "LIVE MODE");
        log.info("Press Ctrl+C to stop.");

        // Shutdown hook — capture final references
        final SpotTickerWebSocketService finalSpotTicker = spotTickerService;
        final CrossCurrencyArbitrageStrategy finalSpotStrategy = spotStrategy;
//        final SpotFuturesArbitrageStrategy finalSpotFuturesStrategy = spotFuturesStrategy;
        final FuturesTickerService finalFuturesTicker = futuresTickerService;
        final LeadLagOrchestrator finalLeadLag = leadLagOrchestrator;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            if (finalLeadLag != null) finalLeadLag.shutdown();
            if (finalSpotStrategy != null) finalSpotStrategy.shutdown();
//            if (finalSpotFuturesStrategy != null) finalSpotFuturesStrategy.shutdown();
            if (finalSpotTicker != null) finalSpotTicker.shutdown();
            if (finalFuturesTicker != null) finalFuturesTicker.shutdown();
            orderClient.disconnect();
            log.info("Shutdown complete.");
        }, "shutdown-hook"));

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
