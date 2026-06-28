package cloudcode.krakenfutures.leadlag;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cloudcode.krakenfutures.config.TradingConfig;
import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.leadlag.monitor.AltcoinLagMonitor;
import cloudcode.krakenfutures.leadlag.monitor.BtcOrderBookMonitor;
import cloudcode.krakenfutures.leadlag.monitor.BtcVolumeMonitor;
import cloudcode.krakenfutures.leadlag.risk.CooldownTracker;
import cloudcode.krakenfutures.leadlag.risk.RiskManager;
import cloudcode.krakenfutures.leadlag.selector.AltcoinCandidate;
import cloudcode.krakenfutures.leadlag.selector.AltcoinSelector;
import cloudcode.krakenfutures.leadlag.selector.CorrelationTracker;
import cloudcode.krakenfutures.leadlag.signal.BtcMoveDetector;
import cloudcode.krakenfutures.leadlag.signal.BtcSignal;
import cloudcode.krakenfutures.leadlag.signal.SignalConfirmation;
import cloudcode.krakenfutures.leadlag.signal.SignalType;
import cloudcode.krakenfutures.leadlag.trade.Position;
import cloudcode.krakenfutures.leadlag.trade.PositionManager;
import cloudcode.krakenfutures.leadlag.trade.TradeExecutor;
import cloudcode.krakenfutures.leadlag.trade.TradeResult;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService;
import cloudcode.krakenfutures.websocket.SpotTickerWebSocketService.TickerData;
import cloudcode.krakenfutures.websocket.KrakenWebSocketClient;

public class LeadLagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(LeadLagOrchestrator.class);

    private final LeadLagConfig leadLagConfig;
    private final TradingConfig tradingConfig;
    private final SpotTickerWebSocketService tickerService;
    private final KrakenWebSocketClient orderClient;

    private final BtcMoveDetector btcMoveDetector;
    private final BtcOrderBookMonitor btcOrderBookMonitor;
    private final BtcVolumeMonitor btcVolumeMonitor;
    private final SignalConfirmation signalConfirmation;
    private final CorrelationTracker correlationTracker;
    private final AltcoinLagMonitor altcoinLagMonitor;
    private final AltcoinSelector altcoinSelector;
    private final TradeExecutor tradeExecutor;
    private final PositionManager positionManager;
    private final CooldownTracker cooldownTracker;
    private final RiskManager riskManager;

    private volatile SignalType latestConfirmedSignal = SignalType.NONE;
    private final Map<String, Double> liquidityScores = new HashMap<>();

    public LeadLagOrchestrator(LeadLagConfig leadLagConfig,
                               TradingConfig tradingConfig,
                               SpotTickerWebSocketService tickerService,
                               KrakenWebSocketClient orderClient) {
        this.leadLagConfig = leadLagConfig;
        this.tradingConfig = tradingConfig;
        this.tickerService = tickerService;
        this.orderClient = orderClient;

        this.btcMoveDetector = new BtcMoveDetector(leadLagConfig);
        this.btcOrderBookMonitor = new BtcOrderBookMonitor();
        this.btcVolumeMonitor = new BtcVolumeMonitor();
        this.signalConfirmation = new SignalConfirmation(leadLagConfig, btcOrderBookMonitor, btcVolumeMonitor);
        this.correlationTracker = new CorrelationTracker();
        this.altcoinLagMonitor = new AltcoinLagMonitor();

        // Initialize liquidity scores (in production these come from Kraken REST 24h volume data)
        for (String alt : leadLagConfig.getApprovedAltcoins()) {
            liquidityScores.put(alt, 0.8);
        }

        this.altcoinSelector = new AltcoinSelector(leadLagConfig, correlationTracker, altcoinLagMonitor, liquidityScores);
        this.tradeExecutor = new TradeExecutor(leadLagConfig, tradingConfig, orderClient);
        this.positionManager = new PositionManager(leadLagConfig, tradeExecutor);
        this.cooldownTracker = new CooldownTracker(leadLagConfig);
        this.riskManager = new RiskManager(leadLagConfig, positionManager, cooldownTracker);
    }

    /**
     * Starts the orchestrator by subscribing to BTC and altcoin ticker streams.
     */
    public void start() {
        log.info("LeadLagOrchestrator starting in {} mode", tradingConfig.isPaperTrading() ? "PAPER" : "LIVE");
        tickerService.addTickListener(this::onTickerTick);
    }

    /**
     * Hook called by SpotTickerWebSocketService whenever any tracked symbol updates.
     */
    public void onTickerTick(String baseAsset) {
        if ("BTC".equals(baseAsset) || "XBT".equals(baseAsset)) {
            onBtcTicker(baseAsset);
        } else if (leadLagConfig.getApprovedAltcoins().contains(baseAsset)) {
            onAltcoinTicker(baseAsset);
        }
    }

    private void onBtcTicker(String baseAsset) {
        TickerData ticker = tickerService.getTicker(baseAsset, "USD");
        if (ticker == null || ticker.last == null) return;

        double price = ticker.last.doubleValue();
        long now = System.currentTimeMillis();

        // Step 1: detect raw BTC move
        btcMoveDetector.onPriceTick(price, now, rawSignal -> {
            // Step 2: confirm via imbalance + volume + consistency
            signalConfirmation.onRawSignal(rawSignal, confirmed -> {
                log.info("CONFIRMED_BTC_SIGNAL {}", confirmed);
                latestConfirmedSignal = confirmed.getType();
                handleConfirmedSignal(confirmed);
            });
        });
    }

    private void onAltcoinTicker(String baseAsset) {
        TickerData ticker = tickerService.getTicker(baseAsset, "USD");
        if (ticker == null || ticker.last == null) return;

        double price = ticker.last.doubleValue();
        altcoinLagMonitor.onPriceTick(baseAsset, price, System.currentTimeMillis());

        // If we have an open position, monitor it
        if (positionManager.hasOpenPosition()) {
            Position active = positionManager.getActivePosition();
            if (active != null && active.getSymbol().equals(baseAsset)) {
                positionManager.onPriceUpdate(price, latestConfirmedSignal, this::onPositionClosed);
            }
        }
    }

    private void handleConfirmedSignal(BtcSignal signal) {
        // Basic pre-trade risk gate (cooldown, open position, halted, drawdown)
        if (!riskManager.canOpenNewTrade(0.0, 10_000)) {
            log.info("Signal received but blocked by risk manager (cooldown/open position/drawdown).");
            return;
        }

        // Compute spreads for candidate altcoins
        Map<String, Double> spreads = computeAltcoinSpreads();

        AltcoinCandidate candidate = altcoinSelector.selectBest(spreads, signal.getType().name());
        if (candidate == null) {
            log.info("No suitable altcoin candidate for signal {}", signal.getType());
            return;
        }

        // Final risk gate with the actual spread of the selected candidate
        if (!riskManager.canOpenNewTrade(candidate.getSpreadPct(), 10_000)) {
            log.info("Candidate {} blocked by risk manager (spread={}%)",
                    candidate.getSymbol(), candidate.getSpreadPct());
            return;
        }

        TickerData altcoinTicker = tickerService.getTicker(candidate.getSymbol(), "USD");
        if (altcoinTicker == null || altcoinTicker.last == null) return;

        Position opened = tradeExecutor.openPosition(candidate.getSymbol(), signal.getType(), altcoinTicker.last.doubleValue());
        if (opened != null) {
            positionManager.openPosition(opened);
            log.info("LEADLAG_OPENED {}", opened);
        }
    }

    private Map<String, Double> computeAltcoinSpreads() {
        Map<String, Double> spreads = new HashMap<>();
        for (String alt : leadLagConfig.getApprovedAltcoins()) {
            TickerData ticker = tickerService.getTicker(alt, "USD");
            if (ticker != null) {
                BigDecimal sp = ticker.pairSpreadPct();
                spreads.put(alt, sp != null ? sp.doubleValue() : 100.0);
            } else {
                spreads.put(alt, 100.0);
            }
        }
        return spreads;
    }

    private void onPositionClosed(TradeResult result) {
        log.info("LEADLAG_CLOSED {}", result);
        cooldownTracker.recordExit();
        riskManager.recordTradePnl(result.getNetPnlEur(), tradingConfig.getTradeSizeUsd());
    }

    public void shutdown() {
        log.info("LeadLagOrchestrator shutting down...");
        positionManager.forceClose(this::onPositionClosed);
    }
}