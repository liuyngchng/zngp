package com.rd.zngp.http;

import com.rd.zngp.middleware.JwtUtil;
import com.rd.zngp.store.Store;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Shared auth extraction and cookie helpers used by API and web handlers.
 */
public final class AuthHelper {

    private static final Logger log = LoggerFactory.getLogger(AuthHelper.class);

    private AuthHelper() {}

    /**
     * Extract auth info from request (Bearer header or cookie).
     * On success, generates a new token (sliding expiration).
     */
    public static AuthInfo extractAuth(FullHttpRequest req) {
        String token = null;

        String authHeader = req.headers().get("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token == null) {
            String cookieStr = req.headers().get("Cookie");
            if (cookieStr != null) {
                Set<Cookie> cookies = ServerCookieDecoder.STRICT.decode(cookieStr);
                for (Cookie c : cookies) {
                    if (JwtUtil.COOKIE_NAME.equals(c.name())) {
                        token = c.value();
                        break;
                    }
                }
            }
        }

        if (token != null) {
            try {
                Claims claims = JwtUtil.parseToken(token);
                long userId = ((Number) claims.get("user_id")).longValue();
                String username = (String) claims.get("username");
                boolean mustChange = claims.get("must_change_password") != null
                    && Boolean.TRUE.equals(claims.get("must_change_password"));
                String newToken = JwtUtil.generateTokenWithFlags(userId, username, mustChange);
                return new AuthInfo(true, userId, username, newToken, mustChange);
            } catch (Exception ignored) {
                // token invalid
            }
        }
        return new AuthInfo(false, 0, "", null, false);
    }

    /** Set HttpOnly cookie on the response (sliding expiration). */
    public static void setTokenCookie(FullHttpResponse resp, String token) {
        io.netty.handler.codec.http.cookie.DefaultCookie cookie =
            new io.netty.handler.codec.http.cookie.DefaultCookie(JwtUtil.COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(72 * 3600);
        resp.headers().add(HttpHeaderNames.SET_COOKIE,
            io.netty.handler.codec.http.cookie.ServerCookieEncoder.STRICT.encode(cookie));
    }

    /** Clear the auth cookie. */
    public static void clearTokenCookie(FullHttpResponse resp) {
        io.netty.handler.codec.http.cookie.DefaultCookie cookie =
            new io.netty.handler.codec.http.cookie.DefaultCookie(JwtUtil.COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        resp.headers().add(HttpHeaderNames.SET_COOKIE,
            io.netty.handler.codec.http.cookie.ServerCookieEncoder.STRICT.encode(cookie));
    }

    /** Add sliding-expiration token to response headers and cookie. */
    public static void applySlidingToken(FullHttpResponse resp, AuthInfo auth) {
        if (auth.token != null && !auth.token.isEmpty()) {
            resp.headers().set("X-New-Token", auth.token);
            setTokenCookie(resp, auth.token);
        }
    }

    public static class AuthInfo {
        public final boolean valid;
        public final long userId;
        public final String username;
        public final String token;
        public final boolean mustChangePassword;

        public AuthInfo(boolean valid, long userId, String username, String token, boolean mustChangePassword) {
            this.valid = valid;
            this.userId = userId;
            this.username = username;
            this.token = token;
            this.mustChangePassword = mustChangePassword;
        }
    }
}