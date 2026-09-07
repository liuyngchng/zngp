package com.rd.zngp.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static helpers for building HTTP responses, reading request bodies, and parsing query strings.
 */
public final class HttpHelpers {

    private static final ObjectMapper mapper = new ObjectMapper();

    private HttpHelpers() {}

    static {
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    public static ObjectMapper objectMapper() { return mapper; }

    // ---- Response builders ----

    public static FullHttpResponse json(int status, Object body) {
        try {
            byte[] data = mapper.writeValueAsBytes(body);
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(data));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
            return resp;
        } catch (Exception e) {
            return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public static FullHttpResponse html(int status, String html) {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(data));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
        return resp;
    }

    public static FullHttpResponse redirect(String location) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        resp.headers().set(HttpHeaderNames.LOCATION, location);
        return resp;
    }

    public static FullHttpResponse text(int status, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(status), Unpooled.wrappedBuffer(data));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, data.length);
        return resp;
    }

    public static void sendAndClose(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        boolean keepAlive = HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(resp);
        } else {
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ---- Error helpers ----

    public static Map<String, String> errorMap(String msg) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    public static Map<String, String> singletonMap(String key, String value) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}