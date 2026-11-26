
package com.smartgeo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public final class JwtUtil {

    private static final long EXPIRATION_TIME = 24 * 60 * 60 * 1000L;

    private JwtUtil() {
    }

    private static SecretKey key() {

        String secret = System.getenv("SMARTGEO_JWT_SECRET");

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "SMARTGEO_JWT_SECRET n'est pas configurée."
            );
        }

        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "Le secret JWT doit contenir au moins 32 octets."
            );
        }

        return Keys.hmacShaKeyFor(secretBytes);
    }

    public static String create(long id, String role) {

        if (id <= 0 || role == null) {
            throw new IllegalArgumentException(
                    "Identifiant ou rôle invalide."
            );
        }

        long now = System.currentTimeMillis();

        return Jwts.builder()
                .subject(Long.toString(id))
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + EXPIRATION_TIME))
                .signWith(key())
                .compact();
    }

    public static Claims parse(String token) {

        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token vide.");
        }

        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
