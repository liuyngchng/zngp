package com.rd.zngp.middleware;

import com.rd.zngp.config.Config;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

/**
 * JWT token generation and parsing, mirroring server/internal/middleware/auth.go.
 */
public class JwtUtil {

    public static final String COOKIE_NAME = "zngp_token";

    private static SecretKey getKey() {
        // Derive a fixed 256-bit key from the configured secret via SHA-256.
        // This avoids weak keys when the secret is shorter than 32 bytes
        // (zero-padding would drastically reduce entropy) and keeps the key
        // usable with HS256 regardless of secret length.
        try {
            byte[] secretBytes = Config.appConfig.auth.jwtSecret.getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secretBytes);
            return Keys.hmacShaKeyFor(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String generateToken(long userId, String username) {
        return generateTokenWithFlags(userId, username, false);
    }

    public static String generateTokenWithFlags(long userId, String username, boolean mustChangePassword) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 72L * 3600 * 1000); // 72 hours

        return Jwts.builder()
            .claim("user_id", userId)
            .claim("username", username)
            .claim("must_change_password", mustChangePassword)
            .setIssuedAt(now)
            .setExpiration(expiry)
            .signWith(getKey(), SignatureAlgorithm.HS256)
            .compact();
    }

    public static Claims parseToken(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(getKey())
            .build()
            .parseClaimsJws(token)
            .getBody();
    }
}