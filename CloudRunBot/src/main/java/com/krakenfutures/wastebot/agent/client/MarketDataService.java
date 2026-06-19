package com.krakenfutures.wastebot.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krakenfutures.wastebot.agent.model.MomentumRank;
import com.krakenfutures.wastebot.agent.util.IndicatorCalculator.Candle;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper objectMapper;

    public MarketDataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Candle> fetchKrakenCandles(String pair, String interval, int limit) throws IOException {
        String url = String.format(
            "https://api.kraken.com/0/public/OHLC?pair=%s&interval=%s",
            pair.replace("/", ""), interval
        );
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Kraken API error: " + response.code());
            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            if (error.isArray() && !error.isEmpty() && !error.get(0).asText("").isEmpty()) {
                throw new IOException("Kraken API: " + error.toString());
            }
            JsonNode result = root.path("result");
            String ohlcKey = result.fieldNames().hasNext() ? result.fieldNames().next() : "";
            JsonNode ohlcArray = result.path(ohlcKey);

            List<Candle> candles = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
            for (JsonNode candle : ohlcArray) {
                Instant ts = Instant.ofEpochSecond(candle.get(0).asLong());
                Candle c = new Candle(
                    ts.atZone(ZoneId.of("UTC")).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC).format(fmt.withZone(ZoneId.of("UTC"))),
                    candle.get(1).asDouble(),
                    candle.get(2).asDouble(),
                    candle.get(3).asDouble(),
                    candle.get(4).asDouble(),
                    candle.get(6).asDouble()
                );
                candles.add(c);
            }
            // Trim to limit
            if (candles.size() > limit) {
                return candles.subList(candles.size() - limit, candles.size());
            }
            return candles;
        }
    }

    public List<MomentumRank> calculateMomentum(List<String> assets, String timeframe) {
        List<MomentumRank> ranks = new ArrayList<>();
        for (String asset : assets) {
            try {
                String pair = asset.replace("/", "");
                String url = String.format("https://api.kraken.com/0/public/Ticker?pair=%s", pair);
                Request request = new Request.Builder().url(url).build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) continue;
                    String body = response.body().string();
                    JsonNode root = objectMapper.readTree(body);
                    JsonNode result = root.path("result");
                    if (!result.isEmpty()) {
                        JsonNode ticker = result.elements().next();
                        double last = ticker.path("c").get(0).asDouble();
                        double open = ticker.path("o").get(0).asDouble();
                        double pct = open > 0 ? ((last - open) / open) * 100.0 : 0.0;
                        ranks.add(new MomentumRank(asset, pct));
                    }
                }
            } catch (IOException e) {
                log.warn("[MOMENTUM] Failed to fetch {}", asset);
                ranks.add(new MomentumRank(asset, 0.0));
            }
        }
        ranks.sort((a, b) -> Double.compare(b.momentum(), a.momentum()));
        return ranks;
    }
}
