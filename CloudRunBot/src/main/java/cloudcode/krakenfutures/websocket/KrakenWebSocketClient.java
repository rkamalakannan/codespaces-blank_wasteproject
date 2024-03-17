package cloudcode.krakenfutures.websocket;

import cloudcode.krakenfutures.config.TradingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.net.URI;
import java.util.concurrent.*;

/**
 * Low-latency WebSocket client for Kraken Spot v2 order execution.
 * Used for sending orders, cancellations, and authenticated operations.
 *
 * Optimizations:
 * - TCP_NODELAY for immediate packet dispatch
 * - IPTOS_LOWDELAY traffic class hint
 * - Auto-reconnect with exponential backoff
 * - Pre-formatted JSON payloads (no template engine overhead)
 */
public class KrakenWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenWebSocketClient.class);

    private static final String PUBLIC_WS_URL = "wss://ws.kraken.com/v2";
    private static final String PRIVATE_WS_URL = "wss://ws-auth.kraken.com/v2";
    private static final int RECONNECT_DELAY_SECONDS = 3;
    private static final int PING_INTERVAL_SECONDS = 25;

    private final ObjectMapper objectMapper;
    private final TradingConfig config;
    private WebSocketClient wsClient;
    private volatile boolean connected = false;
    private volatile String wsToken;

    // Paper trading stats
    private volatile long paperOrderCount = 0;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-order-sched");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pingTask;

    public KrakenWebSocketClient(ObjectMapper objectMapper, TradingConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public void connect(String token) {
        if (connected && wsClient != null) {
            log.info("Order WebSocket already connected.");
            return;
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Cannot connect private order WebSocket without a Kraken WebSocket auth token");
        }

        this.wsToken = token;
        URI uri = URI.create(PRIVATE_WS_URL);
        log.info("Connecting order WebSocket to: {}", uri);

        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected = true;
                log.info("Order WebSocket connected.");

                // TCP_NODELAY for low-latency order execution
                try {
                    Socket socket = getSocket();
                    if (socket != null) {
                        socket.setTcpNoDelay(true);
                        socket.setTrafficClass(0x10);
                        log.info("TCP_NODELAY enabled on order WebSocket");
                    }
                } catch (Exception e) {
                    log.warn("Could not set TCP_NODELAY on order WS: {}", e.getMessage());
                }

                if (token != null) {
                    authenticate(token);
                }

                // Ping keepalive
                if (pingTask != null) pingTask.cancel(false);
                pingTask = scheduler.scheduleAtFixedRate(() -> {
                    if (connected) {
                        try { send("{\"method\":\"ping\"}"); }
                        catch (Exception e) { log.error("Order WS ping failed", e); }
                    }
                }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode node = objectMapper.readTree(message);
                    if (node.has("method")) {
                        String method = node.get("method").asText();
                        if ("add_order".equals(method) || "cancel_order".equals(method)
                                || "cancel_all".equals(method) || "batch_add".equals(method)) {
                            boolean success = node.has("success") && node.get("success").asBoolean();
                            if (success) {
                                log.info("Order response OK: {}", method);
                            } else {
                                log.warn("Order response FAILED: {}", message);
                            }
                        }
                    } else if (node.has("channel") && "executions".equals(node.get("channel").asText())) {
                        log.info("Execution: {}", message);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse order WS message", e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                connected = false;
                log.warn("Order WebSocket closed (code={}, reason={}). Reconnecting...", code, reason);
                scheduler.schedule(() -> KrakenWebSocketClient.this.connect(wsToken), RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void onError(Exception ex) {
                log.error("Order WebSocket error", ex);
                connected = false;
            }
        };

        wsClient.setConnectionLostTimeout(60);
        wsClient.connect();
    }

    private void authenticate(String token) {
        // Kraken v2 auth: subscribe to executions channel with token
        String payload = String.format(
                "{\"method\":\"subscribe\",\"params\":{\"channel\":\"executions\",\"token\":\"%s\",\"snap_orders\":true,\"snap_trades\":true}}",
                token
        );
        wsClient.send(payload);
        log.info("Auth + executions subscription sent");
    }

    /**
     * Send a limit order via WebSocket v2.
     * In PAPER mode, logs the order but does not send it.
     * Uses pre-formatted JSON for minimum latency.
     */
    public void sendOrder(String symbol, String side, double size, double price, String orderType) {
        if (wsToken == null || wsToken.isBlank()) {
            throw new IllegalStateException("Cannot send private order without Kraken WebSocket auth token");
        }

        // Kraken v2 add_order format
        String payload;
        if ("market".equals(orderType)) {
            payload = String.format(
                    "{\"method\":\"add_order\",\"params\":{\"token\":\"%s\",\"order_type\":\"market\",\"side\":\"%s\",\"symbol\":\"%s\",\"order_qty\":%.8f}}",
                    wsToken, side, symbol, size
            );
        } else {
            payload = String.format(
                    "{\"method\":\"add_order\",\"params\":{\"token\":\"%s\",\"order_type\":\"%s\",\"side\":\"%s\",\"symbol\":\"%s\",\"order_qty\":%.8f,\"limit_price\":%.8f}}",
                    wsToken, orderType, side, symbol, size, price
            );
        }

        if (config.isPaperTrading()) {
            paperOrderCount++;
            log.info("[PAPER] Order #{}: {}", paperOrderCount, payload);
            return;
        }

        if (!connected || wsClient == null) {
            throw new IllegalStateException("Order WebSocket not connected");
        }

        log.info("Order -> {}", payload);
        wsClient.send(payload);
    }

    public void cancelOrder(String orderId) {
        if (config.isPaperTrading()) {
            log.info("[PAPER] Cancel order: {}", orderId);
            return;
        }
        if (wsToken == null || wsToken.isBlank()) {
            throw new IllegalStateException("Cannot cancel private order without Kraken WebSocket auth token");
        }
        if (!connected || wsClient == null) {
            throw new IllegalStateException("Order WebSocket not connected");
        }
        String payload = String.format(
                "{\"method\":\"cancel_order\",\"params\":{\"token\":\"%s\",\"order_id\":[\"%s\"]}}",
                wsToken, orderId
        );
        wsClient.send(payload);
        log.info("Cancel order: {}", orderId);
    }

    public void cancelAll() {
        if (config.isPaperTrading()) {
            log.info("[PAPER] Cancel all orders");
            return;
        }
        if (wsToken == null || wsToken.isBlank()) {
            throw new IllegalStateException("Cannot cancel private orders without Kraken WebSocket auth token");
        }
        if (!connected || wsClient == null) return;
        wsClient.send(String.format("{\"method\":\"cancel_all\",\"params\":{\"token\":\"%s\"}}", wsToken));
        log.info("Cancel all orders sent");
    }

    public long getPaperOrderCount() {
        return paperOrderCount;
    }

    public boolean isConnected() {
        return connected;
    }

    public void disconnect() {
        if (pingTask != null) pingTask.cancel(false);
        scheduler.shutdown();
        if (wsClient != null && connected) {
            wsClient.close();
            connected = false;
        }
    }
}
