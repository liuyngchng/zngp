package com.rd.zngp.middleware;

import com.rd.zngp.config.Config;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT token generation and parsing, mirroring server/internal/middleware/auth.go.
 */
public class JwtUtil {

    public static final String COOKIE_NAME = "zngp_token";

    private static SecretKey getKey() {
        byte[] keyBytes = Config.appConfig.auth.jwtSecret.getBytes(StandardCharsets.UTF_8);
        // Ensure key is at least 256 bits for HS256
        byte[] padded = new byte[Math.max(keyBytes.length, 32)];
        System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
        return Keys.hmacShaKeyFor(padded);
    }

    public static String generateToken(long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 72L * 3600 * 1000); // 72 hours

        return Jwts.builder()
            .claim("user_id", userId)
            .claim("username", username)
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