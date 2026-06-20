package com.krakenfutures.bot.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class FuturesOrderWebSocket {
    private static final Logger log = LoggerFactory.getLogger(FuturesOrderWebSocket.class);
    private static final String WS_URL = "wss://futures.kraken.com/ws/v1";
    private static final String DEMO_WS_URL = "wss://demo-futures.kraken.com/ws/v1";
    private static final int PING_INTERVAL_SEC = 30;

    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String apiSecret;
    private final boolean paperMode;
    private final URI wsUri;

    private WebSocketClient wsClient;
    private volatile boolean authenticated = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-fut-order");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-fut-ping");
        t.setDaemon(true);
        return t;
    });
    private volatile String originalChallenge;
    private volatile String signedChallenge;
    private long pendingRequestId = 1L;
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong orderIdSeq = new AtomicLong(1);
    private volatile boolean running = false;

    public FuturesOrderWebSocket(ObjectMapper objectMapper, String apiKey, String apiSecret, boolean paperMode) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.paperMode = paperMode;
        this.wsUri = URI.create(paperMode ? DEMO_WS_URL : WS_URL);
    }

    public void connectAndAuthenticate() {
        if (running) return;
        running = true;

        wsClient = new WebSocketClient(wsUri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                log.info("[FUT_WS] Connected to {}", wsUri);
                requestChallenge();
            }

            @Override
            public void onMessage(String message) {
                handleMessage(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                authenticated = false;
                log.warn("[FUT_WS] Connection closed. code={} reason={}", code, reason);
                if (running) {
                    scheduler.schedule(FuturesOrderWebSocket.this::connectAndAuthenticate, 3, TimeUnit.SECONDS);
                }
            }

            @Override
            public void onError(Exception ex) {
                log.error("[FUT_WS] Error: {}", ex.getMessage());
            }
        };
        wsClient.setConnectionLostTimeout(60);
        wsClient.connect();

        pingScheduler.scheduleAtFixedRate(() -> {
            if (wsClient.isOpen()) {
                wsClient.send("{\"event\":\"ping\",\"reqid\":" + (System.currentTimeMillis() % 100000) + "}");
            }
        }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void requestChallenge() {
        if (!wsClient.isOpen()) return;
        String payload = "{\"event\":\"challenge\",\"reqid\":" + nextReqId() + ",\"api_key\":\"" + apiKey + "\"}";
        log.info("[FUT_WS] Requesting challenge for keyPrefix={}", apiKey.substring(0, Math.min(4, apiKey.length())));
        wsClient.send(payload);
    }

    private void handleMessage(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            String event = node.path("event").asText("");

            if ("challenge".equals(event)) {
                originalChallenge = node.path("message").asText();
                signedChallenge = signChallenge(originalChallenge);
                log.info("[FUT_WS] Challenge received. Signed. Subscribing to private feeds.");
                authenticate();
            } else if ("subscribed".equals(event)) {
                authenticated = true;
                log.info("[FUT_WS] Authenticated. Private feed subscription confirmed.");
            } else if ("success".equals(event)) {
                long reqId = node.path("reqid").asLong(0);
                CompletableFuture<JsonNode> pending = pendingRequests.remove(reqId);
                if (pending != null) pending.complete(node);
            } else if ("error".equals(event)) {
                long reqId = node.path("reqid").asLong(0);
                CompletableFuture<JsonNode> pending = pendingRequests.remove(reqId);
                if (pending != null) pending.completeExceptionally(new RuntimeException("Futures WS error: " + node.path("message").asText()));
                log.error("[FUT_WS] Error event: reqId={} msg={}", reqId, node.path("message").asText());
            }
        } catch (Exception e) {
            log.error("[FUT_WS] Message parse error: {}", e.getMessage());
        }
    }

    private void authenticate() {
        if (!wsClient.isOpen() || signedChallenge == null) return;
        String payload = String.format(
            "{\"event\":\"subscribe\",\"product_ids\":[\"PI_XBTUSD\"],\"feed\":\"open_orders\",\"api_key\":\"%s\",\"original_challenge\":\"%s\",\"signed_challenge\":\"%s\"}",
            apiKey, originalChallenge, signedChallenge
        );
        wsClient.send(payload);
    }

    private String signChallenge(String challengeRaw) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hashed = sha.digest(challengeRaw.getBytes(StandardCharsets.UTF_8));
            byte[] decodedSecret = Base64.getDecoder().decode(apiSecret);
            Mac hmac = Mac.getInstance("HmacSHA512");
            hmac.init(new SecretKeySpec(decodedSecret, "HmacSHA512"));
            byte[] signed = hmac.doFinal(hashed);
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            throw new RuntimeException("Challenge signing failed", e);
        }
    }

    public CompletableFuture<JsonNode> placeMarketOrder(String product, String side, double size, Double slStop, Double tpLimit, String cliOrdId) {
        long reqId = nextReqId();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);

        StringBuilder payload = new StringBuilder();
        payload.append("{\"event\":\"sendorder\",\"reqid\":").append(reqId);
        if (signedChallenge != null) payload.append(",\"api_key\":\"").append(apiKey)
            .append("\",\"original_challenge\":\"").append(originalChallenge)
            .append("\",\"signed_challenge\":\"").append(signedChallenge).append("\"");

        payload.append(",\"tick_latency_ms\":1,\"order_type\":\"market\",\"symbol\":\"").append(product)
            .append("\",\"side\":\"").append(side.toLowerCase())
            .append("\",\"size\":").append(size)
            .append(",\"cliOrdId\":\"").append(cliOrdId != null ? cliOrdId : "cli_" + orderIdSeq.getAndIncrement()).append("\"");

        if (slStop != null) payload.append(",\"stopPrice\":").append(slStop);
        if (tpLimit != null) payload.append(",\"limitPrice\":").append(tpLimit);
        payload.append("}");

        if (paperMode) {
            log.info("[FUT_PAPER] {} | {} @ {} | cliOrdId={}", side, product, size, cliOrdId);
            JsonNode paperResponse = createPaperResponse(reqId, side, product, size);
            future.complete(paperResponse);
            return future;
        }

        if (!authenticated || !wsClient.isOpen()) {
            future.completeExceptionally(new IllegalStateException("Futures WS not authenticated"));
            return future;
        }
        wsClient.send(payload.toString());
        return future.orTimeout(10, TimeUnit.SECONDS)
            .exceptionally(t -> { pendingRequests.remove(reqId); throw new RuntimeException(t); });
    }

    public CompletableFuture<JsonNode> cancelOrder(String cliOrdId) {
        long reqId = nextReqId();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);

        if (paperMode) {
            log.info("[FUT_PAPER] Cancel {}", cliOrdId);
            future.complete(objectMapper.createObjectNode().put("event", "success").put("reqid", reqId));
            return future;
        }

        String payload = String.format(
            "{\"event\":\"cancelorder\",\"reqid\":%d,\"cliOrdId\":\"%s\",\"api_key\":\"%s\",\"original_challenge\":\"%s\",\"signed_challenge\":\"%s\"}",
            reqId, cliOrdId, apiKey, originalChallenge, signedChallenge
        );

        if (!wsClient.isOpen() || !authenticated) {
            future.completeExceptionally(new IllegalStateException("Futures WS not connected/authenticated"));
            return future;
        }
        wsClient.send(payload);
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    private JsonNode createPaperResponse(long reqId, String side, String product, double size) {
        var node = objectMapper.createObjectNode();
        node.put("event", "sendorder");
        node.put("reqid", reqId);
        node.put("result", "placed");
        node.put("order", paperMode ? "PAPER" : "PAPER");
        node.put("side", side);
        node.put("product", product);
        node.put("filledSize", size);
        return node;
    }

    private long nextReqId() {
        return pendingRequestId++;
    }

    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
        pingScheduler.shutdownNow();
        if (wsClient != null) {
            wsClient.close();
        }
        authenticated = false;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public boolean isRunning() {
        return running;
    }
}
