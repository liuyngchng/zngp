package com.rd.zngp.http.handler;

import com.rd.zngp.http.*;
import com.rd.zngp.middleware.JwtUtil;
import com.rd.zngp.model.User;
import com.rd.zngp.store.Store;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Auth API handlers: login, change-password, status.
 */
public class AuthApiHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthApiHandler.class);

    public static void handleLogin(ChannelHandlerContext ctx, FullHttpRequest req) {
        try {
            Map<String, Object> body = RequestHelpers.parseJsonBody(req);
            String username = (String) body.get("username");
            String password = (String) body.get("password");

            if (username == null || password == null) {
                log.warn("login_failed_missing_params");
                HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(400, HttpHelpers.errorMap("username and password required")));
                return;
            }

            Store store = AppContext.getStore();
            User user = store.findUserByUsername(username);
            if (user == null || !BCrypt.checkpw(password, user.passwordHash)) {
                log.warn("login_failed_wrong_credentials: username={}", username);
                HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(401, HttpHelpers.errorMap("invalid username or password")));
                return;
            }

            if (user.mustChangePassword && user.passwordExpiresAt != null) {
                if (LocalDateTime.now().isAfter(user.passwordExpiresAt)) {
                    log.warn("login_failed_password_expired: username={}", username);
                    HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(401, HttpHelpers.errorMap("initial password expired, contact admin")));
                    return;
                }
            }

            String token = JwtUtil.generateTokenWithFlags(user.id, user.username, user.mustChangePassword);
            log.info("login_success: username={}, must_change_password={}", username, user.mustChangePassword);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("token", token);
            result.put("username", user.username);
            if (user.mustChangePassword) {
                result.put("must_change_password", true);
            }

            FullHttpResponse resp = HttpHelpers.json(200, result);
            AuthHelper.setTokenCookie(resp, token);
            HttpHelpers.sendAndClose(ctx, req, resp);
        } catch (Exception e) {
            log.error("login_failed_system_error", e);
            HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(500, HttpHelpers.errorMap("login failed: " + e.getMessage())));
        }
    }

    public static void handleChangePassword(ChannelHandlerContext ctx, FullHttpRequest req, AuthHelper.AuthInfo auth) {
        try {
            Map<String, Object> body = RequestHelpers.parseJsonBody(req);
            String oldPassword = (String) body.get("old_password");
            String newPassword = (String) body.get("new_password");

            if (oldPassword == null || newPassword == null || newPassword.length() < 6) {
                log.warn("change_password_failed_invalid_params: username={}", auth.username);
                HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(400, HttpHelpers.errorMap("old password and new password (min 6 chars) required")));
                return;
            }

            Store store = AppContext.getStore();
            User user = store.findUserByUsername(auth.username);
            if (user == null || !BCrypt.checkpw(oldPassword, user.passwordHash)) {
                log.warn("change_password_failed_wrong_old_password: username={}", auth.username);
                HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(401, HttpHelpers.errorMap("incorrect old password")));
                return;
            }

            String hash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            store.updateUserPassword(auth.userId, hash);
            log.info("change_password_success: username={}, user_id={}", auth.username, auth.userId);

            FullHttpResponse resp = HttpHelpers.json(200, HttpHelpers.singletonMap("message", "password changed"));
            HttpHelpers.sendAndClose(ctx, req, resp);
        } catch (Exception e) {
            log.error("change_password_failed_db_error: username={}", auth.username, e);
            HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(500, HttpHelpers.errorMap("password change failed: " + e.getMessage())));
        }
    }

    public static void handleStatus(ChannelHandlerContext ctx, FullHttpRequest req, AuthHelper.AuthInfo auth) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", auth.username);
        result.put("must_change_password", auth.mustChangePassword);
        HttpHelpers.sendAndClose(ctx, req, HttpHelpers.json(200, result));
    }
}