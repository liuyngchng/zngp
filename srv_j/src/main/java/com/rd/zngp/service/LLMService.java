package com.rd.zngp.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rd.zngp.config.Config;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM service, mirroring server/internal/service/llm.go.
 */
public class LLMService {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class LLMResponse {
        @JsonProperty("choices")
        public List<Choice> choices;

        @JsonProperty("usage")
        public Usage usage;

        @JsonProperty("error")
        public ApiError error;

        public static class Choice {
            @JsonProperty("message")
            public Message message;
        }

        public static class Message {
            @JsonProperty("content")
            public String content;
        }

        public static class Usage {
            @JsonProperty("total_tokens")
            public int totalTokens;
        }

        public static class ApiError {
            @JsonProperty("message")
            public String message;
        }
    }

    public static class ChatResult {
        public final String content;
        public final int tokens;

        public ChatResult(String content, int tokens) {
            this.content = content;
            this.tokens = tokens;
        }
    }

    /**
     * Send a prompt to the LLM. Returns the response text and token count.
     */
    public static ChatResult chatCompletion(String systemPrompt, String userPrompt) throws Exception {
        Config cfg = Config.appConfig;

        Map<String, Object> sysMsg = new LinkedHashMap<>();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        List<Object> messages = new ArrayList<>();
        messages.add(sysMsg);
        messages.add(userMsg);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", cfg.llm.model);
        req.put("messages", messages);
        req.put("temperature", 0.3);
        req.put("max_tokens", 4096);

        String jsonBody = mapper.writeValueAsString(req);

        String apiURL = buildOpenAIURL(cfg.llm.endpoint);

        HttpURLConnection conn = ASRService.createConnection(apiURL, 180_000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + cfg.llm.apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        byte[] bodyBytes = ASRService.readAll(conn, status);

        if (status != 200) {
            throw new Exception("LLM API 返回错误 (" + status + "): " + new String(bodyBytes, StandardCharsets.UTF_8));
        }

        LLMResponse llmResp = mapper.readValue(bodyBytes, LLMResponse.class);

        if (llmResp.error != null) {
            throw new Exception("LLM 错误: " + llmResp.error.message);
        }

        if (llmResp.choices == null || llmResp.choices.isEmpty()) {
            throw new Exception("LLM 返回空结果");
        }

        int tokens = 0;
        if (llmResp.usage != null) {
            tokens = llmResp.usage.totalTokens;
        }

        return new ChatResult(llmResp.choices.get(0).message.content, tokens);
    }

    private static String buildOpenAIURL(String endpoint) {
        String trimmed = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/v1/chat/completions";
    }
}