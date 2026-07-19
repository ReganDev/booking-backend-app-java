package com.dev.bookingapp.javabookingapp.security;

import com.dev.bookingapp.javabookingapp.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret(Base64.getEncoder().encodeToString(new byte[32]));
        config.setAccessTokenExpirationMs(60_000L);
        config.setRefreshTokenExpirationMs(120_000L);
        jwtService = new JwtService(config);
    }

    @Test
    void accessAndRefreshTokensAreNotInterchangeable() {
        UUID userId = UUID.randomUUID();
        String access = jwtService.generateAccessToken(
                userId, null, "user@example.com", "CUSTOMER");
        String refresh = jwtService.generateRefreshToken(userId);

        assertThat(jwtService.validateAccessToken(access)).isTrue();
        assertThat(jwtService.validateRefreshToken(access)).isFalse();
        assertThat(jwtService.validateRefreshToken(refresh)).isTrue();
        assertThat(jwtService.validateAccessToken(refresh)).isFalse();
    }
}
