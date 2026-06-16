package cloudcode.krakenfutures.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cloudcode.krakenfutures.config.TradingConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * REST client for Kraken Spot + Futures APIs.
 *
 * Spot:       https://api.kraken.com
 * Futures:    https://futures.kraken.com/derivatives/api/v3
 *
 * Fetches tradable pairs, futures instruments, and handles
 * authenticated order placement for both markets.
 */
public class KrakenRestClient {

    private static final Logger log = LoggerFactory.getLogger(KrakenRestClient.class);
    private static final String SPOT_BASE_URL = "https://api.kraken.com";
    private static final String FUTURES_BASE_URL = "https://futures.kraken.com/derivatives/api/v3";

    private static final Set<String> FIAT_QUOTES = Set.of(
            "USD", "EUR", "GBP", "CAD", "AUD", "JPY", "CHF"
    );

    private static final int MAX_SPOT_ASSETS_TO_MONITOR = 25;
    private static final double MIN_24H_QUOTE_VOLUME = 250_000.0;
    private static final double MIN_24H_MOVE_PERCENT = 1.0;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final TradingConfig config;

    public KrakenRestClient(ObjectMapper objectMapper, TradingConfig config) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * Fetches all tradable spot asset pairs from Kraken and returns only those
     * that have a fiat quote currency (USD, EUR, GBP, CAD, AUD, JPY, CHF).
     *
     * Returns a map: base asset -> set of quote currencies that exist on Kraken.
     * Uses the v2 symbol format (BTC/USD) from the wsname or altname fields.
     */
    public Map<String, Set<String>> fetchFiatSpotPairs() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Map<String, List<String>> tickerPairsByAsset = new LinkedHashMap<>();

        try {
            JsonNode root = getPublic("/0/public/AssetPairs");
            if (root == null || root.has("error") && root.get("error").size() > 0) {
                log.error("Failed to fetch asset pairs: {}", root);
                return result;
            }

            JsonNode pairs = root.get("result");
            if (pairs == null) return result;

            Iterator<Map.Entry<String, JsonNode>> fields = pairs.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode pairInfo = entry.getValue();

                // Use wsname which has v2 format like "XBT/USD"
                String wsname = pairInfo.has("wsname") ? pairInfo.get("wsname").asText() : null;
                if (wsname == null || !wsname.contains("/")) continue;

                // Skip darkpool pairs
                if (entry.getKey().endsWith(".d")) continue;

                String[] parts = wsname.split("/");
                if (parts.length != 2) continue;

                String base = normalizeBase(parts[0]);
                String quote = parts[1].toUpperCase();

                if (!FIAT_QUOTES.contains(quote)) continue;

                result.computeIfAbsent(base, k -> new LinkedHashSet<>()).add(quote);
                tickerPairsByAsset.computeIfAbsent(base, k -> new ArrayList<>()).add(entry.getKey());
            }

            // Only keep assets that have at least 2 fiat quote currencies (needed for arbitrage)
            result.entrySet().removeIf(e -> e.getValue().size() < 2);
            tickerPairsByAsset.keySet().retainAll(result.keySet());

            result = filterTopVolumeMovingAssets(result, tickerPairsByAsset);

            log.info("Discovered {} high-volume moving assets with multi-fiat pairs from Kraken API", result.size());
            for (Map.Entry<String, Set<String>> e : result.entrySet()) {
                log.debug("  {} -> {}", e.getKey(), e.getValue());
            }

        } catch (Exception e) {
            log.error("Error fetching tradable asset pairs", e);
        }
        return result;
    }

    private Map<String, Set<String>> filterTopVolumeMovingAssets(
            Map<String, Set<String>> assetQuotes,
            Map<String, List<String>> tickerPairsByAsset
    ) {
        if (assetQuotes.isEmpty() || tickerPairsByAsset.isEmpty()) {
            return assetQuotes;
        }

        Map<String, AssetMarketScore> scores = new HashMap<>();

        List<String> allTickerPairs = tickerPairsByAsset.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        final int batchSize = 50;
        for (int i = 0; i < allTickerPairs.size(); i += batchSize) {
            List<String> batch = allTickerPairs.subList(i, Math.min(i + batchSize, allTickerPairs.size()));
            JsonNode tickerRoot = getPublic("/0/public/Ticker?pair=" + String.join(",", batch));

            if (tickerRoot == null || tickerRoot.has("error") && tickerRoot.get("error").size() > 0) {
                log.warn("Failed to fetch ticker batch for liquidity filter: {}", tickerRoot);
                continue;
            }

            JsonNode tickerResult = tickerRoot.get("result");
            if (tickerResult == null || !tickerResult.isObject()) {
                continue;
            }

            for (Map.Entry<String, List<String>> assetEntry : tickerPairsByAsset.entrySet()) {
                String asset = assetEntry.getKey();

                for (String pairKey : assetEntry.getValue()) {
                    JsonNode ticker = tickerResult.get(pairKey);
                    if (ticker == null) {
                        continue;
                    }

                    double last = ticker.path("c").path(0).asDouble(0);
                    double open = ticker.path("o").asDouble(0);
                    double volume = ticker.path("v").path(1).asDouble(0);
                    double vwap = ticker.path("p").path(1).asDouble(last);

                    if (last <= 0 || open <= 0 || volume <= 0 || vwap <= 0) {
                        continue;
                    }

                    double quoteVolume = volume * vwap;
                    double movePercent = Math.abs(last - open) / open * 100.0;

                    scores.computeIfAbsent(asset, ignored -> new AssetMarketScore())
                            .add(quoteVolume, movePercent);
                }
            }
        }

        Map<String, Set<String>> filtered = scores.entrySet().stream()
                .filter(e -> assetQuotes.containsKey(e.getKey()))
                .filter(e -> e.getValue().quoteVolume >= MIN_24H_QUOTE_VOLUME)
                .filter(e -> e.getValue().maxMovePercent >= MIN_24H_MOVE_PERCENT)
                .sorted((a, b) -> Double.compare(b.getValue().score(), a.getValue().score()))
                .limit(MAX_SPOT_ASSETS_TO_MONITOR)
                .collect(
                        LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), assetQuotes.get(entry.getKey())),
                        LinkedHashMap::putAll
                );

        log.info("Liquidity/movement filter kept {} of {} assets. minVolume={}, minMove={}%, maxAssets={}",
                filtered.size(), assetQuotes.size(), MIN_24H_QUOTE_VOLUME, MIN_24H_MOVE_PERCENT, MAX_SPOT_ASSETS_TO_MONITOR);

        return filtered;
    }

    private static class AssetMarketScore {
        private double quoteVolume;
        private double maxMovePercent;

        private void add(double quoteVolume, double movePercent) {
            this.quoteVolume += quoteVolume;
            this.maxMovePercent = Math.max(this.maxMovePercent, movePercent);
        }

        private double score() {
            return quoteVolume * Math.max(maxMovePercent, 0.1);
        }
    }

    /**
     * Fetches all stablecoin/fiat pairs for FX rate derivation.
     * Returns list of pairs like "USDT/EUR", "USDC/GBP" etc.
     */
    public List<String> fetchStablecoinFiatPairs() {
        List<String> result = new ArrayList<>();
        try {
            JsonNode root = getPublic("/0/public/AssetPairs");
            if (root == null) return result;

            JsonNode pairs = root.get("result");
            if (pairs == null) return result;

            Iterator<Map.Entry<String, JsonNode>> fields = pairs.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (entry.getKey().endsWith(".d")) continue;

                JsonNode pairInfo = entry.getValue();
                String wsname = pairInfo.has("wsname") ? pairInfo.get("wsname").asText() : null;
                if (wsname == null || !wsname.contains("/")) continue;

                String[] parts = wsname.split("/");
                if (parts.length != 2) continue;

                String base = parts[0].toUpperCase();
                String quote = parts[1].toUpperCase();

                if ((base.equals("USDT") || base.equals("USDC") || base.equals("DAI"))
                        && FIAT_QUOTES.contains(quote)) {
                    result.add(wsname);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching stablecoin pairs", e);
        }
        log.info("Discovered {} stablecoin/fiat pairs for FX rate derivation", result.size());
        return result;
    }

    public String getWebSocketToken() {
        if (config.getSpotApiKey().isEmpty() || config.getSpotApiSecret().isEmpty()) {
            log.error("Cannot get Kraken WebSocket token — KRAKEN_API_KEY or KRAKEN_API_SECRET is missing");
            return null;
        }

        try {
            String endpoint = "/0/private/GetWebSocketsToken";
            String nonce = String.valueOf(System.currentTimeMillis());
            String postData = "nonce=" + nonce;
            String signature = signSpotRequest(endpoint, nonce, postData);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SPOT_BASE_URL + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("API-Key", config.getSpotApiKey())
                    .header("API-Sign", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(postData))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());

            if (root.has("error") && root.get("error").isArray() && root.get("error").size() > 0) {
                log.error("Failed to get Kraken WebSocket token: {}", root.get("error"));
                return null;
            }

            String token = root.path("result").path("token").asText(null);
            if (token == null || token.isBlank()) {
                log.error("Kraken WebSocket token response did not contain token: {}", root);
                return null;
            }

            log.info("Kraken WebSocket auth token acquired");
            return token;
        } catch (Exception e) {
            log.error("Failed to get Kraken WebSocket token", e);
            return null;
        }
    }

    // ===== Futures REST API =====

    /**
     * Fetch all tradable futures instruments from Kraken Derivatives API.
     * Returns a list of product IDs (e.g. PF_XBTUSD, PF_ETHUSD, FI_XBTUSD_250627).
     */
    public List<String> fetchFuturesInstruments() {
        List<String> result = new ArrayList<>();
        try {
            JsonNode root = getFuturesPublic("/instruments");
            if (root == null || !"success".equals(root.path("result").asText())) {
                log.error("Failed to fetch futures instruments: {}", root);
                return result;
            }

            JsonNode instruments = root.get("instruments");
            if (instruments == null || !instruments.isArray()) return result;

            for (JsonNode inst : instruments) {
                boolean tradeable = inst.has("tradeable") && inst.get("tradeable").asBoolean();
                if (!tradeable) continue;

                String symbol = inst.has("symbol") ? inst.get("symbol").asText() : null;
                if (symbol != null) {
                    result.add(symbol);
                }
            }

            log.info("Discovered {} tradeable futures instruments", result.size());
        } catch (Exception e) {
            log.error("Error fetching futures instruments", e);
        }
        return result;
    }

    /**
     * Place a futures order via Derivatives REST API.
     * Requires KRAKEN_FUTURES_KEY and KRAKEN_FUTURES_SECRET.
     *
     * @param symbol    product ID (e.g. PF_XBTUSD)
     * @param side      "buy" or "sell"
     * @param size      order size
     * @param price     limit price (null for market orders)
     * @param orderType "lmt" or "mkt"
     * @return true if order was accepted
     */
    public boolean placeFuturesOrder(String symbol, String side, double size, Double price, String orderType) {
        if (config.isPaperTrading()) {
            log.info("[PAPER] Futures order: {} {} x{} @ {} ({})", side, symbol, size, price, orderType);
            return true;
        }

        if (config.getFuturesApiKey().isEmpty()) {
            log.error("Cannot place futures order — KRAKEN_FUTURES_KEY not set");
            return false;
        }

        try {
            String postData = String.format("orderType=%s&symbol=%s&side=%s&size=%.8f",
                    orderType, symbol, side, size);
            if (price != null && "lmt".equals(orderType)) {
                postData += String.format("&limitPrice=%.8f", price);
            }

            JsonNode response = postFuturesAuthenticated("/sendorder", postData);
            if (response != null && "success".equals(response.path("result").asText())) {
                log.info("Futures order placed: {} {} x{} @ {}", side, symbol, size, price);
                return true;
            } else {
                log.error("Futures order failed: {}", response);
                return false;
            }
        } catch (Exception e) {
            log.error("Futures order error", e);
            return false;
        }
    }

    /**
     * Cancel a futures order.
     */
    public boolean cancelFuturesOrder(String orderId) {
        if (config.isPaperTrading()) {
            log.info("[PAPER] Cancel futures order: {}", orderId);
            return true;
        }

        try {
            String postData = "order_id=" + orderId;
            JsonNode response = postFuturesAuthenticated("/cancelorder", postData);
            return response != null && "success".equals(response.path("result").asText());
        } catch (Exception e) {
            log.error("Futures cancel error", e);
            return false;
        }
    }

    /**
     * Get open futures positions.
     */
    public JsonNode getFuturesPositions() {
        try {
            return getFuturesAuthenticated("/openpositions");
        } catch (Exception e) {
            log.error("Error fetching futures positions", e);
            return null;
        }
    }

    public JsonNode getPublic(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SPOT_BASE_URL + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("GET {} failed", endpoint, e);
            return null;
        }
    }

    public JsonNode getFuturesPublic(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FUTURES_BASE_URL + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("Futures GET {} failed", endpoint, e);
            return null;
        }
    }

    /**
     * Authenticated GET for Futures API.
     * Uses HMAC-SHA512 signing with SHA-256 intermediate hash.
     */
    private JsonNode getFuturesAuthenticated(String endpoint) {
        try {
            String nonce = String.valueOf(System.currentTimeMillis());
            String authent = signFuturesRequest(endpoint, nonce, "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FUTURES_BASE_URL + endpoint + "?" + nonce))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("APIKey", config.getFuturesApiKey())
                    .header("Authent", authent)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("Futures authenticated GET {} failed", endpoint, e);
            return null;
        }
    }

    /**
     * Authenticated POST for Futures API.
     */
    private JsonNode postFuturesAuthenticated(String endpoint, String postData) {
        try {
            String authent = signFuturesRequest(endpoint, "", postData);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FUTURES_BASE_URL + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("APIKey", config.getFuturesApiKey())
                    .header("Authent", authent)
                    .POST(HttpRequest.BodyPublishers.ofString(postData))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("Futures authenticated POST {} failed", endpoint, e);
            return null;
        }
    }

    /**
     * Sign a Kraken Spot private REST request.
     * API-Sign = HMAC-SHA512(uri_path + SHA256(nonce + postData), base64decode(secret)).
     */
    private String signSpotRequest(String endpoint, String nonce, String postData) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] sha256Hash = sha256.digest((nonce + postData).getBytes(StandardCharsets.UTF_8));

            byte[] endpointBytes = endpoint.getBytes(StandardCharsets.UTF_8);
            byte[] message = new byte[endpointBytes.length + sha256Hash.length];
            System.arraycopy(endpointBytes, 0, message, 0, endpointBytes.length);
            System.arraycopy(sha256Hash, 0, message, endpointBytes.length, sha256Hash.length);

            byte[] secretBytes = Base64.getDecoder().decode(config.getSpotApiSecret());
            Mac hmac = Mac.getInstance("HmacSHA512");
            hmac.init(new SecretKeySpec(secretBytes, "HmacSHA512"));

            return Base64.getEncoder().encodeToString(hmac.doFinal(message));
        } catch (Exception e) {
            log.error("Failed to sign Kraken Spot request", e);
            return "";
        }
    }

    /**
     * Sign a Futures API request using HMAC-SHA512(SHA-256(postData + nonce + endpoint), base64decode(secret)).
     */
    private String signFuturesRequest(String endpoint, String nonce, String postData) {
        try {
            String data = postData + nonce + endpoint;
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(data.getBytes(StandardCharsets.UTF_8));

            byte[] secretBytes = Base64.getDecoder().decode(config.getFuturesApiSecret());
            Mac hmac = Mac.getInstance("HmacSHA512");
            hmac.init(new SecretKeySpec(secretBytes, "HmacSHA512"));
            byte[] signed = hmac.doFinal(hash);

            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            log.error("Failed to sign futures request", e);
            return "";
        }
    }

    /**
     * Normalize Kraken base symbols to standard names.
     * Kraken uses XBT for Bitcoin, XXBT in some contexts.
     */
    private static String normalizeBase(String krakenBase) {
        return switch (krakenBase.toUpperCase()) {
            case "XBT", "XXBT" -> "BTC";
            case "XETH" -> "ETH";
            case "XXRP" -> "XRP";
            case "XLTC" -> "LTC";
            case "XXLM" -> "XLM";
            case "XXMR" -> "XMR";
            case "XZEC" -> "ZEC";
            case "XETC" -> "ETC";
            case "XREP" -> "REP";
            case "XMLN" -> "MLN";
            default -> krakenBase.toUpperCase();
        };
    }
}
