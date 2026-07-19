package com.dev.bookingapp.javabookingapp.security;

import com.dev.bookingapp.javabookingapp.config.JwtConfig;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String ACCESS_TOKEN_TYPE = "access";
    public static final String REFRESH_TOKEN_TYPE = "refresh";

    private final JwtConfig jwtConfig;

    public String generateAccessToken(UUID userId, UUID businessId, String email, String role) {
        Map<String, Object> claims = new HashMap<>();
        if (businessId != null) {
            claims.put("businessId", businessId.toString());
        }
        claims.put("email", email);
        claims.put("role", role);
        claims.put("tokenType", ACCESS_TOKEN_TYPE);

        return Jwts.builder()
                .claims(claims)
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtConfig.getAccessTokenExpirationMs()))
                .signWith(getSigningKey())
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
                .claim("tokenType", REFRESH_TOKEN_TYPE)
                .subject(userId.toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtConfig.getRefreshTokenExpirationMs()))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean validateAccessToken(String token) {
        return validateToken(token) && ACCESS_TOKEN_TYPE.equals(getTokenType(token));
    }

    public boolean validateRefreshToken(String token) {
        return validateToken(token) && REFRESH_TOKEN_TYPE.equals(getTokenType(token));
    }

    public String getTokenType(String token) {
        return extractAllClaims(token).get("tokenType", String.class);
    }

    public UUID getUserIdFromToken(String token) {
        Claims claims = extractAllClaims(token);
        return UUID.fromString(claims.getSubject());
    }

    public UUID getBusinessIdFromToken(String token) {
        Claims claims = extractAllClaims(token);
        String businessId = claims.get("businessId", String.class);
        return businessId != null ? UUID.fromString(businessId) : null;
    }

    public String getEmailFromToken(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("email", String.class);
    }

    public String getRoleFromToken(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("role", String.class);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtConfig.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public Long getAccessTokenExpirationMs() {
        return jwtConfig.getAccessTokenExpirationMs();
    }

    public Long getRefreshTokenExpirationMs() {
        return jwtConfig.getRefreshTokenExpirationMs();
    }
}
