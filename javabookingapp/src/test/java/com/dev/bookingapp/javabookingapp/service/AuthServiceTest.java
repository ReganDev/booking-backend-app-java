package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.LoginRequest;
import com.dev.bookingapp.javabookingapp.dto.request.RefreshTokenRequest;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.EmailNotVerifiedException;
import com.dev.bookingapp.javabookingapp.exception.UnauthorizedException;
import com.dev.bookingapp.javabookingapp.mapper.BusinessMapper;
import com.dev.bookingapp.javabookingapp.mapper.UserMapper;
import com.dev.bookingapp.javabookingapp.repository.BusinessRepository;
import com.dev.bookingapp.javabookingapp.repository.RefreshTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import com.dev.bookingapp.javabookingapp.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock BusinessRepository businessRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock UserMapper userMapper;
    @Mock BusinessMapper businessMapper;
    @Mock EmailVerificationService emailVerificationService;
    @InjectMocks AuthService authService;

    @Test
    void validCredentialsForUnverifiedCustomerReturnStableFailure() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("user@example.com")
                .passwordHash("encoded")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(false)
                .build();
        when(userRepository.findByEmailIgnoreCase("user@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        LoginRequest request = new LoginRequest();
        request.setEmail(" User@Example.com ");
        request.setPassword("password");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(EmailNotVerifiedException.class);
        verify(passwordEncoder).matches("password", "encoded");
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any());
        verify(jwtService, never()).generateAccessToken(any(), any(), any(), any());
    }

    @Test
    void refreshRejectsTokenWithoutRefreshType() {
        when(jwtService.validateRefreshToken("access-token")).thenReturn(false);
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("access-token");

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid refresh token");
        verifyNoInteractions(refreshTokenRepository);
    }
}
