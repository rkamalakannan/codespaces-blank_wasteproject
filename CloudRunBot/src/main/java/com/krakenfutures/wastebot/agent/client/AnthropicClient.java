package com.krakenfutures.wastebot.agent.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class AnthropicClient {
    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public AnthropicClient(ObjectMapper objectMapper) {
        this.okHttpClient = new OkHttpClient();
        this.objectMapper = objectMapper;
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
    }

    public String callClaude(String systemPrompt, String userMessage) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY environment variable is not set");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", DEFAULT_MODEL);
        root.put("max_tokens", 4000);
        root.put("system", systemPrompt);

        var messages = objectMapper.createArrayNode();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.put("content", userMessage);
        messages.add(message);
        root.set("messages", messages);

        String jsonPayload = objectMapper.writeValueAsString(root);

        RequestBody body = RequestBody.create(
            jsonPayload,
            MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                log.error("Claude API call failed. Status: {}, Code: {}, Error: {}", response.message(), response.code(), errorBody);
                throw new IOException("Unexpected response code: " + response.code() + ", error: " + errorBody);
            }

            String responseBody = response.body().string();
            JsonNode responseJson = objectMapper.readTree(responseBody);
            return responseJson.path("content").get(0).path("text").asText();
        }
    }
}
