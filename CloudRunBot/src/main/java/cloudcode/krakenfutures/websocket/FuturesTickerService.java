package cloudcode.krakenfutures.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Kraken Derivatives (Futures) WebSocket ticker feed.
 *
 * Connects to wss://futures.kraken.com/ws/v1 and subscribes to the
 * "ticker" feed for futures product IDs (e.g. PF_XBTUSD, PF_ETHUSD).
 *
 * Maintains a real-time cache of futures ticker data including:
 * bid, ask, last, volume, funding rate, mark price, open interest.
 */
public class FuturesTickerService {

    private static final Logger log = LoggerFactory.getLogger(FuturesTickerService.class);

    private static final String KRAKEN_FUTURES_WS_URL = "wss://futures.kraken.com/ws/v1";
    private static final int PING_INTERVAL_SECONDS = 25;
    private static final int RECONNECT_DELAY_SECONDS = 3;

    private final ObjectMapper objectMapper;

    // Price cache: productId -> FuturesTickerData
    private final ConcurrentHashMap<String, FuturesTickerData> priceCache = new ConcurrentHashMap<>(32);

    // Listeners notified on every tick with the product ID
    private final List<Consumer<String>> tickListeners = new CopyOnWriteArrayList<>();

    private volatile boolean connected = false;
    private volatile WebSocketClient wsClient;
    private volatile long lastMessageNanos = 0;

    // Product IDs to subscribe to (set by caller)
    private List<String> subscriptionProducts = List.of();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-futures-sched");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private ScheduledFuture<?> pingTask;

    public FuturesTickerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Set product IDs to subscribe to (e.g. "PF_XBTUSD", "PF_ETHUSD"). Call before start(). */
    public void setSubscriptionProducts(List<String> productIds) {
        this.subscriptionProducts = List.copyOf(productIds);
    }

    public void start() {
        if (subscriptionProducts.isEmpty()) {
            log.error("No futures products set — call setSubscriptionProducts() before start()");
            return;
        }
        connectAndSubscribe();
    }

    private void connectAndSubscribe() {
        URI uri = URI.create(KRAKEN_FUTURES_WS_URL);
        log.info("Connecting to Kraken Futures WebSocket: {}", uri);

        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected = true;
                lastMessageNanos = System.nanoTime();
                log.info("Connected to Kraken Futures WS. Subscribing to {} products...", subscriptionProducts.size());

                enableTcpNoDelay();
                subscribeToProducts();

                // Ping keepalive
                if (pingTask != null) pingTask.cancel(false);
                pingTask = scheduler.scheduleAtFixedRate(() -> {
                    if (connected) {
                        try {
                            send("{\"event\":\"ping\"}");
                        } catch (Exception e) {
                            log.error("Futures ping failed", e);
                        }
                    }
                }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void onMessage(String message) {
                lastMessageNanos = System.nanoTime();
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                connected = false;
                log.warn("Futures WS closed (code={}, reason={}). Reconnecting in {}s...",
                        code, reason, RECONNECT_DELAY_SECONDS);
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                log.error("Futures WS error", ex);
                connected = false;
                scheduleReconnect();
            }

            private void enableTcpNoDelay() {
                try {
                    Socket socket = getSocket();
                    if (socket != null) {
                        socket.setTcpNoDelay(true);
                        socket.setTrafficClass(0x10);
                        log.info("TCP_NODELAY enabled on Futures WS");
                    }
                } catch (Exception e) {
                    log.warn("Could not set TCP_NODELAY on Futures WS: {}", e.getMessage());
                }
            }
        };

        wsClient.setConnectionLostTimeout(60);
        wsClient.connect();
    }

    private void scheduleReconnect() {
        scheduler.schedule(this::connectAndSubscribe, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void subscribeToProducts() {
        try {
            // Kraken Futures WS v1 subscribe format
            var msg = objectMapper.createObjectNode();
            msg.put("event", "subscribe");
            msg.put("feed", "ticker");
            var products = objectMapper.createArrayNode();
            for (String pid : subscriptionProducts) {
                products.add(pid);
            }
            msg.set("product_ids", products);

            String payload = objectMapper.writeValueAsString(msg);
            log.info("Subscribing to futures ticker: {} products", subscriptionProducts.size());
            wsClient.send(payload);
        } catch (Exception e) {
            log.error("Failed to subscribe to futures ticker", e);
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);

            if (node.has("feed")) {
                String feed = node.get("feed").asText();
                if ("ticker".equals(feed) && node.has("product_id")) {
                    processTickerUpdate(node);
                } else if ("ticker_snapshot".equals(feed) && node.has("product_id")) {
                    processTickerUpdate(node);
                }
            } else if (node.has("event")) {
                String event = node.get("event").asText();
                if ("subscribed".equals(event)) {
                    log.info("Futures subscribed: feed={}, products={}",
                            node.has("feed") ? node.get("feed").asText() : "?",
                            node.has("product_ids") ? node.get("product_ids") : "?");
                } else if ("error".equals(event)) {
                    log.error("Futures WS error: {}", node.has("message") ? node.get("message").asText() : message);
                }
                // "info" and "pong" events — no action needed
            }
        } catch (Exception e) {
            log.error("Failed to parse futures message: {}",
                    message.length() > 200 ? message.substring(0, 200) : message, e);
        }
    }

    private void processTickerUpdate(JsonNode data) {
        try {
            String productId = data.get("product_id").asText();

            BigDecimal bid = getDecimal(data, "bid");
            BigDecimal ask = getDecimal(data, "ask");
            BigDecimal last = getDecimal(data, "last");
            BigDecimal volume = getDecimal(data, "volume");
            BigDecimal markPrice = getDecimal(data, "markPrice");
            BigDecimal indexPrice = getDecimal(data, "index");
            BigDecimal openInterest = getDecimal(data, "openInterest");
            BigDecimal fundingRate = getDecimal(data, "funding_rate");
            BigDecimal fundingRatePrediction = getDecimal(data, "funding_rate_prediction");
            String leverage = data.has("leverage") ? data.get("leverage").asText() : null;
            String pair = data.has("pair") ? data.get("pair").asText() : null;
            String tag = data.has("tag") ? data.get("tag").asText() : null;
            boolean suspended = data.has("suspended") && data.get("suspended").asBoolean();

            long nowNanos = System.nanoTime();
            FuturesTickerData ticker = new FuturesTickerData(
                    productId, pair, tag, bid, ask, last, volume,
                    markPrice, indexPrice, openInterest,
                    fundingRate, fundingRatePrediction,
                    leverage, suspended, nowNanos
            );

            priceCache.put(productId, ticker);

            // Notify listeners
            for (Consumer<String> listener : tickListeners) {
                try {
                    listener.accept(productId);
                } catch (Exception e) {
                    log.error("Futures tick listener error", e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process futures ticker: {}", data, e);
        }
    }

    private static BigDecimal getDecimal(JsonNode node, String field) {
        JsonNode val = node.get(field);
        if (val == null || val.isNull()) return null;
        try {
            return new BigDecimal(val.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Register a listener called on every futures tick with the product ID */
    public void addTickListener(Consumer<String> listener) {
        tickListeners.add(listener);
    }

    public ConcurrentHashMap<String, FuturesTickerData> getPriceCache() {
        return priceCache;
    }

    public FuturesTickerData getTicker(String productId) {
        return priceCache.get(productId);
    }

    public boolean isConnected() {
        return connected;
    }

    public long nanosSinceLastMessage() {
        return System.nanoTime() - lastMessageNanos;
    }

    public void shutdown() {
        if (pingTask != null) pingTask.cancel(false);
        scheduler.shutdown();
        if (wsClient != null) wsClient.close();
    }

    /**
     * Futures ticker data with funding rate, mark price, open interest.
     */
    public static class FuturesTickerData {
        public final String productId;
        public final String pair;          // e.g. "XBT:USD"
        public final String tag;           // "perpetual", "month", "quarter"
        public final BigDecimal bid;
        public final BigDecimal ask;
        public final BigDecimal last;
        public final BigDecimal volume;
        public final BigDecimal markPrice;
        public final BigDecimal indexPrice;
        public final BigDecimal openInterest;
        public final BigDecimal fundingRate;
        public final BigDecimal fundingRatePrediction;
        public final String leverage;
        public final boolean suspended;
        public final long receivedNanos;

        public FuturesTickerData(String productId, String pair, String tag,
                                 BigDecimal bid, BigDecimal ask, BigDecimal last, BigDecimal volume,
                                 BigDecimal markPrice, BigDecimal indexPrice, BigDecimal openInterest,
                                 BigDecimal fundingRate, BigDecimal fundingRatePrediction,
                                 String leverage, boolean suspended, long receivedNanos) {
            this.productId = productId;
            this.pair = pair;
            this.tag = tag;
            this.bid = bid;
            this.ask = ask;
            this.last = last;
            this.volume = volume;
            this.markPrice = markPrice;
            this.indexPrice = indexPrice;
            this.openInterest = openInterest;
            this.fundingRate = fundingRate;
            this.fundingRatePrediction = fundingRatePrediction;
            this.leverage = leverage;
            this.suspended = suspended;
            this.receivedNanos = receivedNanos;
        }

        public long ageMs() {
            return (System.nanoTime() - receivedNanos) / 1_000_000;
        }

        @Override
        public String toString() {
            return String.format("%s bid=%s ask=%s last=%s mark=%s funding=%s oi=%s age=%dms",
                    productId, bid, ask, last, markPrice, fundingRate, openInterest, ageMs());
        }
    }
}
