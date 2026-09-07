package com.rd.zngp.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.FullHttpRequest;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers for reading JSON bodies and parsing query strings.
 */
public final class RequestHelpers {

    private RequestHelpers() {}

    public static Map<String, Object> parseJsonBody(FullHttpRequest req) throws IOException {
        byte[] data = new byte[req.content().readableBytes()];
        req.content().readBytes(data);
        return HttpHelpers.objectMapper().readValue(data, Map.class);
    }

    public static <T> T parseJsonBody(FullHttpRequest req, Class<T> clazz) throws IOException {
        byte[] data = new byte[req.content().readableBytes()];
        req.content().readBytes(data);
        return HttpHelpers.objectMapper().readValue(data, clazz);
    }

    public static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    params.put(
                        URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    );
                } catch (Exception ignored) {}
            }
        }
        return params;
    }

    public static int parseInt(String s, int defaultVal) {
        if (s == null || s.isEmpty()) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}