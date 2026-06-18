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
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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

    private final AtomicLong requestId = new AtomicLong(1);
    private final Map<Long, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

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
            return;
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Cannot connect private order WebSocket without a Kraken WebSocket auth token");
        }

        this.wsToken = token;
        URI uri = URI.create(PRIVATE_WS_URL);

        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected = true;
                log.info("ORDER_WS_CONNECTED to {}", uri);
                log.info("ORDER_WS_HANDSHAKE status={} message={} headers={}",
                        handshake.getHttpStatus(),
                        handshake.getHttpStatusMessage(),
                        handshake.iterateHttpFields());

                // TCP_NODELAY for low-latency order execution
                try {
                    Socket socket = getSocket();
                    if (socket != null) {
                        socket.setTcpNoDelay(true);
                        socket.setTrafficClass(0x10);
                    }
                } catch (Exception e) {
                    log.warn("ORDER_WS_SOCKET_OPT_FAILED reason={}", e.getMessage());
                }

                if (token != null) {
                    authenticate(token);
                }

                // Ping keepalive
                if (pingTask != null) pingTask.cancel(false);
                pingTask = scheduler.scheduleAtFixedRate(() -> {
                    if (connected) {
                        try { send("{\"method\":\"ping\"}"); }
                        catch (Exception e) { log.error("ORDER_WS_PING_FAILED reason={}", e.getMessage()); }
                    }
                }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void onMessage(String message) {
                try {
                    JsonNode node = objectMapper.readTree(message);

                    if (isAuthOrErrorMessage(node)) {
                        log.warn("ORDER_WS_AUTH_OR_ERROR raw={}", redactSensitive(message));
                    }

                    if (node.has("method")) {
                        String method = node.get("method").asText();
                        if ("add_order".equals(method)) {
                            long reqId = node.path("req_id").asLong(-1);
                            PendingOrder pending = pendingOrders.remove(reqId);
                            boolean success = node.has("success") && node.get("success").asBoolean();

                            if (pending == null) {
                                if (!success) {
                                    log.warn("ORDER_FAILED side=? symbol=? reason={}", extractError(node));
                                }
                                return;
                            }

                            if (success) {
                                log.info("ORDER_OK side={} symbol={} size={} req_id={}",
                                        pending.side, pending.symbol, pending.size, reqId);
                            } else {
                                String reason = extractError(node);
                                log.warn("ORDER_FAILED side={} symbol={} size={} req_id={} reason={}",
                                        pending.side, pending.symbol, pending.size, reqId, reason);

                                if ("sell".equals(pending.side) && pending.retryCount < pending.maxRetries) {
                                    int nextRetry = pending.retryCount + 1;
                                    log.warn("SELL_RETRY_SCHEDULED symbol={} size={} retry={}/{} reason={}",
                                            pending.symbol, pending.size, nextRetry, pending.maxRetries, reason);
                                    scheduler.schedule(() -> sendMarketOrder(
                                                    pending.symbol,
                                                    pending.side,
                                                    pending.size,
                                                    nextRetry,
                                                    pending.maxRetries
                                            ),
                                            750L * nextRetry,
                                            TimeUnit.MILLISECONDS
                                    );
                                }
                            }
                        } else if ("cancel_order".equals(method) || "cancel_all".equals(method) || "batch_add".equals(method)) {
                            boolean success = node.has("success") && node.get("success").asBoolean();
                            if (!success) {
                                log.warn("ORDER_ACTION_FAILED method={} reason={} raw={}",
                                        method, extractError(node), redactSensitive(message));
                            }
                        } else if ("subscribe".equals(method)) {
                            boolean success = node.has("success") && node.get("success").asBoolean(false);
                            if (success) {
                                log.info("ORDER_WS_AUTH_SUBSCRIBE_OK raw={}", redactSensitive(message));
                            } else {
                                log.error("ORDER_WS_AUTH_SUBSCRIBE_FAILED reason={} raw={}",
                                        extractError(node), redactSensitive(message));
                            }
                        }
                    } else if (node.has("channel") && "executions".equals(node.get("channel").asText())) {
                        log.info("EXECUTION {}", redactSensitive(message));
                    }
                } catch (Exception e) {
                    log.error("ORDER_WS_PARSE_FAILED reason={} raw={}", e.getMessage(), redactSensitive(message));
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                connected = false;
                log.warn("ORDER_WS_CLOSED code={} reason={} remote={}", code, reason, remote);
                scheduler.schedule(() -> KrakenWebSocketClient.this.connect(wsToken), RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void onError(Exception ex) {
                log.error("ORDER_WS_ERROR type={} reason={}", ex.getClass().getName(), ex.getMessage(), ex);
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
        log.info("ORDER_WS_AUTH_SUBSCRIBE_SENT payload={}", redactSensitive(payload));
        wsClient.send(payload);
    }

    /**
     * Send a market order via WebSocket v2.
     * In PAPER mode, logs the order but does not send it.
     */
    public void sendOrder(String symbol, String side, double size, double price, String orderType) {
        sendMarketOrder(symbol, side, size, 0, "sell".equals(side) ? 3 : 0);
    }

    private void sendMarketOrder(String symbol, String side, double size, int retryCount, int maxRetries) {
        if (wsToken == null || wsToken.isBlank()) {
            throw new IllegalStateException("Cannot send private order without Kraken WebSocket auth token");
        }

        long reqId = requestId.getAndIncrement();

        String payload = String.format(
                "{\"method\":\"add_order\",\"req_id\":%d,\"params\":{\"token\":\"%s\",\"order_type\":\"market\",\"side\":\"%s\",\"symbol\":\"%s\",\"order_qty\":%.8f}}",
                reqId, wsToken, side, symbol, size
        );

        if (config.isPaperTrading()) {
            paperOrderCount++;
            log.info("ORDER_SUBMITTED mode=PAPER side={} symbol={} size={} req_id={} retry={}/{}",
                    side, symbol, size, reqId, retryCount, maxRetries);
            log.info("ORDER_OK mode=PAPER side={} symbol={} size={} req_id={}",
                    side, symbol, size, reqId);
            return;
        }

        if (!connected || wsClient == null) {
            throw new IllegalStateException("Order WebSocket not connected");
        }

        pendingOrders.put(reqId, new PendingOrder(symbol, side, size, retryCount, maxRetries));
        log.info("ORDER_SUBMITTED side={} symbol={} size={} req_id={} retry={}/{}",
                side, symbol, size, reqId, retryCount, maxRetries);
        wsClient.send(payload);
    }

    private static String extractError(JsonNode node) {
        if (node.has("error")) {
            return node.get("error").toString();
        }
        if (node.has("errorMessage")) {
            return node.get("errorMessage").asText();
        }
        if (node.has("message")) {
            return node.get("message").asText();
        }
        if (node.has("result") && !node.path("success").asBoolean(true)) {
            return node.get("result").toString();
        }
        return node.toString();
    }

    private static boolean isAuthOrErrorMessage(JsonNode node) {
        if (node.has("error") || node.has("errorMessage")) {
            return true;
        }
        if (node.has("success") && !node.path("success").asBoolean()) {
            return true;
        }
        if (node.has("method") && "subscribe".equals(node.path("method").asText())) {
            return true;
        }
        if (node.has("event") && "error".equals(node.path("event").asText())) {
            return true;
        }
        return false;
    }

    private static String redactSensitive(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replaceAll("(\"token\"\\s*:\\s*\")[^\"]+(\")", "$1***REDACTED***$2")
                .replaceAll("(\"api_key\"\\s*:\\s*\")[^\"]+(\")", "$1***REDACTED***$2")
                .replaceAll("(\"signed_challenge\"\\s*:\\s*\")[^\"]+(\")", "$1***REDACTED***$2");
    }

    private static class PendingOrder {
        final String symbol;
        final String side;
        final double size;
        final int retryCount;
        final int maxRetries;

        PendingOrder(String symbol, String side, double size, int retryCount, int maxRetries) {
            this.symbol = symbol;
            this.side = side;
            this.size = size;
            this.retryCount = retryCount;
            this.maxRetries = maxRetries;
        }
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
