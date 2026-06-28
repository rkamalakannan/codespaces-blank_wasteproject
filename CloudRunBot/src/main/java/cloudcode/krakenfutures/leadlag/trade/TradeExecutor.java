package cloudcode.krakenfutures.leadlag.trade;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cloudcode.krakenfutures.config.TradingConfig;
import cloudcode.krakenfutures.leadlag.config.LeadLagConfig;
import cloudcode.krakenfutures.leadlag.signal.SignalType;
import cloudcode.krakenfutures.websocket.KrakenWebSocketClient;

public class TradeExecutor {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutor.class);

    private final LeadLagConfig leadLagConfig;
    private final TradingConfig tradingConfig;
    private final KrakenWebSocketClient orderClient;

    public TradeExecutor(LeadLagConfig leadLagConfig, TradingConfig tradingConfig, KrakenWebSocketClient orderClient) {
        this.leadLagConfig = leadLagConfig;
        this.tradingConfig = tradingConfig;
        this.orderClient = orderClient;
    }

    /**
     * Opens a position based on the signal direction and altcoin symbol.
     * BULLISH → Spot BUY. BEARISH → Perp SHORT.
     * Returns the opened Position, or null if order failed.
     */
    public Position openPosition(String altcoinSymbol, SignalType signalType, double currentPrice) {
        Position.Side side = signalType == SignalType.BULLISH ? Position.Side.LONG : Position.Side.SHORT;
        double positionSizeEur = leadLagConfig.getPositionSizeEur();
        double quantity = positionSizeEur / currentPrice;

        String sideStr = side == Position.Side.LONG ? "buy" : "sell";
        String krakenPair = altcoinSymbol + "/USD";
        String orderType = "market";

        if (tradingConfig.isPaperTrading()) {
            log.info("[PAPER] Would {} {} qty={} at ~{}", sideStr, krakenPair, quantity, currentPrice);
            return new Position(altcoinSymbol, side, currentPrice, quantity, System.currentTimeMillis());
        }

        try {
            // Spot market order for both directions (in real production, shorts would route through the futures client).
            KrakenWebSocketClient.OrderRequest order = new KrakenWebSocketClient.OrderRequest(
                    krakenPair, sideStr, quantity, null, orderType);
            orderClient.sendBatchOrder(List.of(order));
            log.info("LEADLAG_OPEN side={} symbol={} qty={} entry={}", side, krakenPair, quantity, currentPrice);
            return new Position(altcoinSymbol, side, currentPrice, quantity, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Failed to open lead-lag position for {}: {}", altcoinSymbol, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Closes an existing position with a market order.
     * Returns the closed TradeResult with fee-adjusted PnL.
     */
    public TradeResult closePosition(Position position, double currentPrice, ExitReason reason) {
        String krakenPair = position.getSymbol() + "/USD";
        // Closing: LONG → sell, SHORT → buy
        String closeSide = position.getSide() == Position.Side.LONG ? "sell" : "buy";
        double quantity = position.getQuantity();
        double grossPnlPct = position.getUnrealizedPnlPct();
        double grossPnlEur = grossPnlPct / 100.0 * leadLagConfig.getPositionSizeEur();

        // Compute Kraken fees for the round trip
        // Entry leg: spot taker fee (BULLISH → spot buy, BEARISH → spot sell)
        // Exit leg: spot taker fee (closing the spot position)
        double entryFeeEur = leadLagConfig.getFeeCostEur(leadLagConfig.getPositionSizeEur(), false);
        double exitFeeEur = leadLagConfig.getFeeCostEur(leadLagConfig.getPositionSizeEur(), false);
        double totalFeeEur = entryFeeEur + exitFeeEur;

        if (tradingConfig.isPaperTrading()) {
            log.info("[PAPER] Would close {} {} qty={} at ~{} reason={} fee={:.2f} EUR",
                    closeSide, krakenPair, quantity, currentPrice, reason, totalFeeEur);
        } else {
            try {
                KrakenWebSocketClient.OrderRequest order = new KrakenWebSocketClient.OrderRequest(
                        krakenPair, closeSide, quantity, null, "market");
                orderClient.sendBatchOrder(List.of(order));
                log.info("LEADLAG_CLOSE side={} symbol={} qty={} exit={} reason={} fee={:.2f} EUR",
                        closeSide, krakenPair, quantity, currentPrice, reason, totalFeeEur);
            } catch (Exception e) {
                log.error("Failed to close lead-lag position for {}: {}", position.getSymbol(), e.getMessage(), e);
            }
        }

        long exitTs = System.currentTimeMillis();
        return new TradeResult(position.getSymbol(), position.getSide(),
                position.getEntryPrice(), currentPrice, quantity,
                grossPnlPct, grossPnlEur, totalFeeEur, reason,
                position.getEntryTimestamp(), exitTs,
                tradingConfig.isPaperTrading());
    }
}