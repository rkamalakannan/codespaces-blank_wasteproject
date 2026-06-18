package cloudcode.krakenfutures.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cloudcode.krakenfutures.config.TradingConfig;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final String futuresWsUrl;
    private static final int PING_INTERVAL_SECONDS = 25;
    private static final int RECONNECT_DELAY_SECONDS = 3;

    private final ObjectMapper objectMapper;
    private final TradingConfig config;

    // Challenge-response auth state
    private volatile String originalChallenge;
    private volatile String signedChallenge;

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

    public FuturesTickerService(ObjectMapper objectMapper, TradingConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
        this.futuresWsUrl = config.isPaperTrading()
                ? "wss://demo-futures.kraken.com/ws/v1"
                : "wss://futures.kraken.com/ws/v1";
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
        URI uri = URI.create(futuresWsUrl);
        log.info("Connecting to Kraken Futures WebSocket: {}", uri);

        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected = true;
                lastMessageNanos = System.nanoTime();
                log.info("FUTURES_WS_CONNECTED url={}", futuresWsUrl);
                log.info("FUTURES_WS_HANDSHAKE status={} message={} headers={}",
                        handshake.getHttpStatus(),
                        handshake.getHttpStatusMessage(),
                        handshake.iterateHttpFields());

                enableTcpNoDelay();
                requestChallenge();

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

                if (message.contains("\"event\":\"error\"")
                        || message.contains("\"event\":\"challenge\"")
                        || message.contains("\"error\"")
                        || message.contains("\"success\":false")) {
                    log.warn("FUTURES_WS_AUTH_OR_ERROR_RAW {}", redactSensitive(message));
                }

                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                connected = false;
                log.warn("FUTURES_WS_CLOSED code={} reason={} remote={} url={}",
                        code, reason, remote, futuresWsUrl);
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                log.error("FUTURES_WS_ERROR type={} reason={}", ex.getClass().getName(), ex.getMessage(), ex);
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

    private void requestChallenge() {
        try {
            if (config.getFuturesApiKey() == null || config.getFuturesApiKey().isBlank()) {
                log.error("FUTURES_WS_AUTH_SKIPPED reason=missing_KRAKEN_FUTURES_KEY");
                subscribeToProducts();
                return;
            }

            var msg = objectMapper.createObjectNode();
            msg.put("event", "challenge");
            msg.put("api_key", config.getFuturesApiKey());

            String payload = objectMapper.writeValueAsString(msg);
            log.info("FUTURES_WS_CHALLENGE_SENT payload={}", redactSensitive(payload));
            wsClient.send(payload);
        } catch (Exception e) {
            log.error("FUTURES_WS_CHALLENGE_SEND_FAILED reason={}", e.getMessage(), e);
        }
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

            // Include challenge if available
            if (originalChallenge != null && signedChallenge != null) {
                msg.put("api_key", config.getFuturesApiKey());
                msg.put("original_challenge", originalChallenge);
                msg.put("signed_challenge", signedChallenge);
            }

            String payload = objectMapper.writeValueAsString(msg);
            log.info("FUTURES_WS_SUBSCRIBE_SENT products={} authIncluded={} payload={}",
                    subscriptionProducts.size(),
                    originalChallenge != null && signedChallenge != null,
                    redactSensitive(payload));
            wsClient.send(payload);
        } catch (Exception e) {
            log.error("FUTURES_WS_SUBSCRIBE_FAILED reason={}", e.getMessage(), e);
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
                if ("challenge".equals(event)) {
                    handleChallenge(node);
                } else if ("subscribed".equals(event)) {
                    log.info("FUTURES_WS_SUBSCRIBED feed={} products={}",
                            node.has("feed") ? node.get("feed").asText() : "?",
                            node.has("product_ids") ? node.get("product_ids") : "?");
                } else if ("error".equals(event)) {
                    log.error("FUTURES_WS_ERROR_EVENT message={} raw={}",
                            node.has("message") ? node.get("message").asText() : "?",
                            redactSensitive(message));
                } else if ("info".equals(event) || "pong".equals(event)) {
                    // quiet
                } else {
                    log.warn("FUTURES_WS_UNKNOWN_EVENT event={} raw={}", event, redactSensitive(message));
                }
            } else {
                log.warn("FUTURES_WS_UNKNOWN_MESSAGE raw={}", redactSensitive(message));
            }
        } catch (Exception e) {
            log.error("FUTURES_WS_PARSE_FAILED reason={} raw={}",
                    e.getMessage(),
                    redactSensitive(message),
                    e);
        }
    }

    private void handleChallenge(JsonNode node) {
        log.info("FUTURES_WS_CHALLENGE_RESPONSE raw={}", redactSensitive(node.toString()));

        String challenge = node.path("message").asText();
        if (challenge != null && !challenge.isEmpty()) {
            log.info("FUTURES_WS_CHALLENGE_RECEIVED length={}", challenge.length());
            this.originalChallenge = challenge;
            this.signedChallenge = signChallenge(challenge);
            if (signedChallenge != null) {
                log.info("FUTURES_WS_CHALLENGE_SIGNED signedLength={}", signedChallenge.length());
                subscribeToProducts();
            } else {
                log.error("FUTURES_WS_CHALLENGE_SIGN_FAILED reason=signedChallenge_is_null");
            }
        } else {
            log.error("FUTURES_WS_CHALLENGE_INVALID raw={}", redactSensitive(node.toString()));
        }
    }

    private String signChallenge(String challenge) {
        try {
            if (config.getFuturesApiSecret() == null || config.getFuturesApiSecret().isBlank()) {
                log.error("FUTURES_WS_CHALLENGE_SIGN_FAILED reason=missing_KRAKEN_FUTURES_SECRET");
                return null;
            }

            // 1. SHA-256 hash of challenge
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(challenge.getBytes(StandardCharsets.UTF_8));

            // 2. Base64-decode secret
            byte[] secretBytes = Base64.getDecoder().decode(config.getFuturesApiSecret());

            // 3. HMAC-SHA-512 of hash using secret
            Mac hmac = Mac.getInstance("HmacSHA512");
            hmac.init(new SecretKeySpec(secretBytes, "HmacSHA512"));
            byte[] signed = hmac.doFinal(hash);

            // 4. Base64-encode result
            return Base64.getEncoder().encodeToString(signed);
        } catch (IllegalArgumentException e) {
            log.error("FUTURES_WS_CHALLENGE_SIGN_FAILED reason=futures_secret_is_not_valid_base64");
            return null;
        } catch (Exception e) {
            log.error("FUTURES_WS_CHALLENGE_SIGN_FAILED reason={}", e.getMessage(), e);
            return null;
        }
    }

    private static String redactSensitive(String text) {
        if (text == null) {
            return null;
        }
        return text
                .replaceAll("(\"api_key\"\\s*:\\s*\")[^\"]+(\")", "$1***REDACTED***$2")
                .replaceAll("(\"signed_challenge\"\\s*:\\s*\")[^\"]+(\")", "$1***REDACTED***$2");
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
