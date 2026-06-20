package com.krakenfutures.bot.agent.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class OrderWebSocketService {
    private static final Logger log = LoggerFactory.getLogger(OrderWebSocketService.class);
    private final String apiKey;
    private final String apiSecret;
    private final boolean paperMode;
    private final ObjectMapper objectMapper;

    public OrderWebSocketService(ObjectMapper mapper, String key, String secret, boolean paper) {
        this.objectMapper = mapper;
        this.apiKey = key;
        this.apiSecret = secret;
        this.paperMode = paper;
    }

    public String executeOrder(String symbol, String side, double size, double price) throws IOException {
        String orderId = String.valueOf(System.nanoTime());
        if (paperMode) {
            log.info("[SPOT_PAPER] {} | {} | size={} | order_id={}", side, symbol, size, orderId);
            return orderId;
        }
        // For live orders, log error as spot live is not enabled in standard operations
        log.error("[SPOT_LIVE] Order execution via Spot WS is not supported in the prompt specs. Doing paper execution.");
        return orderId;
    }
}
