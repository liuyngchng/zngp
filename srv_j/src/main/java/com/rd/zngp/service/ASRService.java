package com.rd.zngp.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rd.zngp.config.Config;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASR service, mirroring server/internal/service/asr.go.
 * Sends audio to Aliyun Bailian ASR (OpenAI-compatible) endpoint.
 */
public class ASRService {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class ASRResponse {
        @JsonProperty("id")
        public String id;

        @JsonProperty("choices")
        public List<Choice> choices;

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

        public static class ApiError {
            @JsonProperty("message")
            public String message;
            @JsonProperty("type")
            public String type;
        }
    }

    /**
     * Transcribe audio file. Returns the transcription text.
     */
    public static String transcribeAudio(String audioPath) throws Exception {
        Config cfg = Config.appConfig;

        byte[] data;
        try {
            data = Files.readAllBytes(Paths.get(audioPath));
        } catch (IOException e) {
            throw new Exception("读取音频文件失败: " + e.getMessage(), e);
        }

        String b64 = Base64.getEncoder().encodeToString(data);

        String mimeType = "audio/wav";
        if (audioPath.toLowerCase().endsWith(".mp3")) {
            mimeType = "audio/mpeg";
        }

        String dataURI = "data:" + mimeType + ";base64," + b64;

        String baseURL = cfg.asr.baseUrl.replace("{WorkspaceId}", cfg.asr.workspaceId);
        String apiURL = baseURL + "/chat/completions";

        // Build request body
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        inputAudio.put("data", dataURI);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "input_audio");
        content.put("input_audio", inputAudio);

        List<Object> contentList = new ArrayList<>();
        contentList.add(content);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", contentList);

        List<Object> messages = new ArrayList<>();
        messages.add(message);

        Map<String, Object> asrOptions = new LinkedHashMap<>();
        asrOptions.put("enable_itn", false);

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("model", cfg.asr.model);
        reqBody.put("messages", messages);
        reqBody.put("stream", false);
        reqBody.put("asr_options", asrOptions);

        String jsonBody = mapper.writeValueAsString(reqBody);

        HttpURLConnection conn = createConnection(apiURL, 120_000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + cfg.asr.apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        byte[] bodyBytes = readAll(conn, status);

        if (status != 200) {
            throw new Exception("ASR API 返回错误 (" + status + "): " + new String(bodyBytes, StandardCharsets.UTF_8));
        }

        ASRResponse asrResp = mapper.readValue(bodyBytes, ASRResponse.class);

        if (asrResp.error != null) {
            throw new Exception("ASR 错误: " + asrResp.error.message);
        }

        if (asrResp.choices == null || asrResp.choices.isEmpty()) {
            throw new Exception("ASR 返回空结果");
        }

        return asrResp.choices.get(0).message.content;
    }

    static byte[] readAll(HttpURLConnection conn, int status) throws IOException {
        java.io.InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    /**
     * Creates an HttpURLConnection that trusts all certificates (mirrors Go's InsecureSkipVerify).
     */
    static HttpURLConnection createConnection(String urlStr, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn;
        if (url.getProtocol().equalsIgnoreCase("https")) {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            HttpsURLConnection https = (HttpsURLConnection) url.openConnection();
            https.setSSLSocketFactory(sc.getSocketFactory());
            https.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
            conn = https;
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "zngp-server-java");
        return conn;
    }
}