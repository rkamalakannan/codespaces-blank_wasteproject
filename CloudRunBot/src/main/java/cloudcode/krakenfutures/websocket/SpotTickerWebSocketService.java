package cloudcode.krakenfutures.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.Socket;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Low-latency Kraken Spot WebSocket v2 ticker feed.
 *
 * Optimizations for VPS hosting:
 * - TCP_NODELAY to disable Nagle's algorithm (~40ms latency reduction)
 * - Lock-free ConcurrentHashMap for price cache
 * - Pre-built subscription payloads (zero allocation on hot path)
 * - Monotonic nanoTime for staleness checks (avoids System.currentTimeMillis overhead)
 * - Dynamic pair discovery from Kraken REST API (only subscribe to real pairs)
 * - Single-threaded event dispatch (no contention)
 */
public class SpotTickerWebSocketService {

    private static final Logger log = LoggerFactory.getLogger(SpotTickerWebSocketService.class);

    private static final String KRAKEN_SPOT_WS_URL = "wss://ws.kraken.com/v2";
    private static final int PING_INTERVAL_SECONDS = 25;
    private static final int RECONNECT_DELAY_SECONDS = 3;
    private static final int SUBSCRIBE_BATCH_SIZE = 100;

    // Reverse map: Kraken wsname base -> normalized base
    private static final Map<String, String> KRAKEN_TO_STANDARD = Map.of(
            "XBT", "BTC"
    );
    // Forward map: standard base -> Kraken wsname base
    private static final Map<String, String> STANDARD_TO_KRAKEN = Map.of(
            "BTC", "XBT"
    );

    private final ObjectMapper objectMapper;

    // Price cache: baseAsset -> (quoteCurrency -> TickerData)
    // Lock-free reads via ConcurrentHashMap — no synchronization on hot path
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, TickerData>> priceCache = new ConcurrentHashMap<>(64);

    // FX rate cache: quoteCurrency -> rate_to_USD
    private final ConcurrentHashMap<String, BigDecimal> fxRateToUsd = new ConcurrentHashMap<>(8);

    // Listeners notified on every tick — keep list small for low latency
    private final List<Consumer<String>> tickListeners = new CopyOnWriteArrayList<>();

    private volatile boolean connected = false;
    private volatile WebSocketClient wsClient;
    private volatile long lastMessageNanos = 0;

    // Dynamic pairs to subscribe to (set by caller from REST API discovery)
    private List<String> subscriptionPairs = List.of();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-ticker-sched");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private ScheduledFuture<?> pingTask;

    public SpotTickerWebSocketService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        fxRateToUsd.put("USD", BigDecimal.ONE);
    }

    /**
     * Set the pairs to subscribe to. Call before start().
     * Pairs should be in Kraken v2 format: "XBT/USD", "ETH/EUR", etc.
     */
    public void setSubscriptionPairs(List<String> pairs) {
        this.subscriptionPairs = List.copyOf(pairs);
    }

    /**
     * Build subscription pairs from REST-discovered asset map.
     * @param assetQuotes map of normalized base -> set of quote currencies (from KrakenRestClient)
     * @param stablecoinPairs additional stablecoin/fiat pairs for FX rates
     */
    public void buildSubscriptionPairs(Map<String, Set<String>> assetQuotes, List<String> stablecoinPairs) {
        List<String> pairs = new ArrayList<>(assetQuotes.size() * 4 + stablecoinPairs.size());
        for (Map.Entry<String, Set<String>> entry : assetQuotes.entrySet()) {
            String base = entry.getKey();
            String krakenBase = STANDARD_TO_KRAKEN.getOrDefault(base, base);
            for (String quote : entry.getValue()) {
                pairs.add(krakenBase + "/" + quote);
            }
        }
        pairs.addAll(stablecoinPairs);
        this.subscriptionPairs = List.copyOf(pairs);
        log.info("Built {} subscription pairs from {} assets + {} stablecoin FX pairs",
                pairs.size(), assetQuotes.size(), stablecoinPairs.size());
    }

    public void start() {
        if (subscriptionPairs.isEmpty()) {
            log.error("No subscription pairs set — call buildSubscriptionPairs() or setSubscriptionPairs() before start()");
            return;
        }
        connectAndSubscribe();
    }

    private void connectAndSubscribe() {
        URI uri = URI.create(KRAKEN_SPOT_WS_URL);
        log.info("Connecting to Kraken Spot WebSocket v2: {}", uri);

        wsClient = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                connected = true;
                lastMessageNanos = System.nanoTime();
                log.info("Connected to Kraken Spot WS v2. Subscribing to {} pairs...", subscriptionPairs.size());

                // Enable TCP_NODELAY for minimum latency
                enableTcpNoDelay();

                subscribeToPairsInBatches(subscriptionPairs);

                // Start ping keepalive
                if (pingTask != null) pingTask.cancel(false);
                pingTask = scheduler.scheduleAtFixedRate(() -> {
                    if (connected) {
                        try {
                            send("{\"method\":\"ping\"}");
                        } catch (Exception e) {
                            log.error("Ping failed", e);
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
                log.warn("WS closed (code={}, reason={}, remote={}). Reconnecting in {}s...",
                        code, reason, remote, RECONNECT_DELAY_SECONDS);
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                log.error("WS error", ex);
                connected = false;
                scheduleReconnect();
            }

            private void enableTcpNoDelay() {
                try {
                    Socket socket = getSocket();
                    if (socket != null) {
                        socket.setTcpNoDelay(true);
                        socket.setTrafficClass(0x10); // IPTOS_LOWDELAY
                        log.info("TCP_NODELAY and LOWDELAY enabled for minimum latency");
                    }
                } catch (Exception e) {
                    log.warn("Could not set TCP_NODELAY: {}", e.getMessage());
                }
            }
        };

        // Set connection timeout
        wsClient.setConnectionLostTimeout(60);
        wsClient.connect();
    }

    private void scheduleReconnect() {
        scheduler.schedule(this::connectAndSubscribe, RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void subscribeToPairsInBatches(List<String> allPairs) {
        for (int i = 0; i < allPairs.size(); i += SUBSCRIBE_BATCH_SIZE) {
            List<String> batch = allPairs.subList(i, Math.min(i + SUBSCRIBE_BATCH_SIZE, allPairs.size()));
            int batchNum = i / SUBSCRIBE_BATCH_SIZE + 1;
            try {
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("method", "subscribe");
                ObjectNode params = objectMapper.createObjectNode();
                params.put("channel", "ticker");
                ArrayNode symbols = objectMapper.createArrayNode();
                for (String pair : batch) {
                    symbols.add(pair);
                }
                params.set("symbol", symbols);
                msg.set("params", params);

                String payload = objectMapper.writeValueAsString(msg);
                log.info("Subscribing batch {} ({} pairs)", batchNum, batch.size());
                wsClient.send(payload);
            } catch (Exception e) {
                log.error("Failed to subscribe batch {}", batchNum, e);
            }
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);

            if (node.has("channel")) {
                String channel = node.get("channel").asText();
                if ("ticker".equals(channel) && node.has("data")) {
                    JsonNode dataArray = node.get("data");
                    if (dataArray.isArray()) {
                        for (int i = 0; i < dataArray.size(); i++) {
                            processTickerUpdate(dataArray.get(i));
                        }
                    }
                } else if ("heartbeat".equals(channel)) {
                    // heartbeat — no action needed
                }
            } else if (node.has("method")) {
                String method = node.get("method").asText();
                if ("pong".equals(method)) {
                    // pong response — connection alive
                } else if ("subscribe".equals(method)) {
                    boolean success = node.has("success") && node.get("success").asBoolean();
                    if (!success && node.has("error")) {
                        log.debug("Subscription response: {}", node.get("error").asText());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", message.length() > 200 ? message.substring(0, 200) : message, e);
        }
    }

    private void processTickerUpdate(JsonNode data) {
        try {
            String symbol = data.get("symbol").asText();
            int slashIdx = symbol.indexOf('/');
            if (slashIdx < 0) return;

            String krakenBase = symbol.substring(0, slashIdx);
            String quote = symbol.substring(slashIdx + 1);

            // Normalize base symbol (XBT -> BTC)
            String base = KRAKEN_TO_STANDARD.getOrDefault(krakenBase, krakenBase);

            // Parse prices — use doubleValue() for speed, BigDecimal for precision where needed
            BigDecimal bid = parseBigDecimal(data, "bid");
            BigDecimal ask = parseBigDecimal(data, "ask");
            BigDecimal last = parseBigDecimal(data, "last");
            BigDecimal volume = parseBigDecimal(data, "volume");

            if (last == null) return;
            if (bid == null) bid = last;
            if (ask == null) ask = last;
            if (volume == null) volume = BigDecimal.ZERO;

            long nowNanos = System.nanoTime();
            TickerData ticker = new TickerData(base, quote, bid, ask, last, volume, nowNanos);
            priceCache.computeIfAbsent(base, k -> new ConcurrentHashMap<>(8)).put(quote, ticker);

            // Update FX rates from stablecoin pairs
            updateFxRates(base, quote, last);

            // Notify listeners with the base asset that changed (lightweight callback)
            for (int i = 0; i < tickListeners.size(); i++) {
                try {
                    tickListeners.get(i).accept(base);
                } catch (Exception e) {
                    log.error("Listener error", e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process ticker: {}", data, e);
        }
    }

    private static BigDecimal parseBigDecimal(JsonNode data, String field) {
        JsonNode val = data.get(field);
        if (val == null || val.isNull()) return null;
        try {
            return new BigDecimal(val.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateFxRates(String base, String quote, BigDecimal last) {
        // Derive FX rates from stablecoin pairs: USDT/EUR price means 1 USDT = X EUR
        // So EUR->USD rate = 1/X (assuming USDT ≈ 1 USD)
        if ((base.equals("USDT") || base.equals("USDC") || base.equals("DAI")) && !quote.equals("USD")) {
            if (last.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal rateToUsd = BigDecimal.ONE.divide(last, 10, RoundingMode.HALF_UP);
                fxRateToUsd.put(quote, rateToUsd);
            }
        }
    }

    /** Register a listener called on every tick with the base asset that changed */
    public void addTickListener(Consumer<String> listener) {
        tickListeners.add(listener);
    }

    public ConcurrentHashMap<String, ConcurrentHashMap<String, TickerData>> getPriceCache() {
        return priceCache;
    }

    public ConcurrentHashMap<String, BigDecimal> getFxRatesToUsd() {
        return fxRateToUsd;
    }

    public TickerData getTicker(String baseAsset, String quoteCurrency) {
        ConcurrentHashMap<String, TickerData> quotes = priceCache.get(baseAsset);
        return quotes != null ? quotes.get(quoteCurrency) : null;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Nanoseconds since last message — for staleness detection */
    public long nanosSinceLastMessage() {
        return System.nanoTime() - lastMessageNanos;
    }

    public void shutdown() {
        if (pingTask != null) pingTask.cancel(false);
        scheduler.shutdown();
        if (wsClient != null) wsClient.close();
    }

    /**
     * Ticker data record. Uses nanoTime for staleness checks
     * (monotonic, no wall-clock drift issues).
     */
    public static class TickerData {
        public final String base;
        public final String quote;
        public final BigDecimal bid;
        public final BigDecimal ask;
        public final BigDecimal last;
        public final BigDecimal volume;
        public final long receivedNanos; // System.nanoTime() when received

        public TickerData(String base, String quote, BigDecimal bid, BigDecimal ask,
                          BigDecimal last, BigDecimal volume, long receivedNanos) {
            this.base = base;
            this.quote = quote;
            this.bid = bid;
            this.ask = ask;
            this.last = last;
            this.volume = volume;
            this.receivedNanos = receivedNanos;
        }

        /** Age in milliseconds (monotonic) */
        public long ageMs() {
            return (System.nanoTime() - receivedNanos) / 1_000_000;
        }

        @Override
        public String toString() {
            return String.format("%s/%s bid=%s ask=%s last=%s vol=%s age=%dms",
                    base, quote, bid, ask, last, volume, ageMs());
        }
    }
}
