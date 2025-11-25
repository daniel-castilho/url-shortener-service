package com.example.urlshortener.infra.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.function.Function;

@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final int MINIMUM_SECRET_LENGTH = 32;

    @Value("${app.jwt.secret:9a4f2c8d3b7a1e6f4c5d8e9a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}") // 24 hours
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms:604800000}") // 7 days
    private long jwtRefreshExpirationMs;

    /**
     * Validates JWT secret configuration on application startup.
     * Ensures the secret meets minimum security requirements.
     * 
     * @throws IllegalStateException if secret is not properly configured
     */
    @PostConstruct
    public void validateSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret must be configured. " +
                            "Set app.jwt.secret in application.yaml or APP_JWT_SECRET environment variable.");
        }

        if (jwtSecret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    String.format(
                            "JWT secret must be at least %d characters long (current: %d). " +
                                    "Use a strong random secret for production.",
                            MINIMUM_SECRET_LENGTH,
                            jwtSecret.length()));
        }

        // Warn if using the default hardcoded secret
        if (jwtSecret.equals("9a4f2c8d3b7a1e6f4c5d8e9a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c")) {
            log.warn("⚠️  WARNING: Using default JWT secret! This is INSECURE for production. " +
                    "Set APP_JWT_SECRET environment variable with a strong random secret.");
        } else {
            log.info("✅ JWT secret configured (length: {})", jwtSecret.length());
        }

        log.info("✅ JWT token expiration: {} ms ({} hours)", jwtExpirationMs, jwtExpirationMs / 3600000);
        log.info("✅ JWT refresh token expiration: {} ms ({} days)", jwtRefreshExpirationMs,
                jwtRefreshExpirationMs / 86400000);
    }

    private javax.crypto.SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return generateToken(userPrincipal.getUsername());
    }

    public String generateToken(String email) {
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date((new Date()).getTime() + jwtExpirationMs))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public String generateRefreshToken(String email) {
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date())
                .expiration(new Date((new Date()).getTime() + jwtRefreshExpirationMs))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean validateToken(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith((javax.crypto.SecretKey) getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
