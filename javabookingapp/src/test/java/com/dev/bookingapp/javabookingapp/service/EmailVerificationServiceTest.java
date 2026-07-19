package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.EmailVerificationToken;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.EmailDeliveryException;
import com.dev.bookingapp.javabookingapp.repository.EmailVerificationTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.RefreshTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock EmailVerificationTokenRepository tokenRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock ResendEmailSender emailSender;
    @InjectMocks EmailVerificationService service;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expiry", Duration.ofHours(24));
        ReflectionTestUtils.setField(service, "resendInterval", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "frontendUrl", "https://app.example.com/");
        user = User.builder()
                .id(UUID.randomUUID())
                .email("customer@example.com")
                .firstName("Casey")
                .lastName("Customer")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(false)
                .build();
    }

    @Test
    void issuingUsesOpaqueHashAndRevokesPreviousTokens() {
        when(emailSender.isConfigured()).thenReturn(true);

        service.issueFor(user);

        verify(tokenRepository).revokeActiveByUserId(eq(user.getId()), any());
        ArgumentCaptor<EmailVerificationToken> captor =
                ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(captor.capture());
        EmailVerificationToken stored = captor.getValue();
        assertThat(stored.getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getEmailSnapshot()).isEqualTo(user.getEmail());
        assertThat(stored.getExpiresAt()).isAfter(OffsetDateTime.now().plusHours(23));
        verify(emailSender).send(
                eq("BookingBase"),
                eq(user.getEmail()),
                isNull(),
                eq("Verify your email address"),
                contains("https://app.example.com/verify-email?token=")
        );
    }

    @Test
    void issuingFailsWhenEmailDeliveryIsNotConfigured() {
        when(emailSender.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.issueFor(user))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void validTokenIsSingleUseAndRevokesRefreshTokens() {
        String raw = "valid-opaque-token";
        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(EmailVerificationService.hash(raw))
                .emailSnapshot(user.getEmail())
                .expiresAt(OffsetDateTime.now().plusHours(1))
                .build();
        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        service.verify(raw);

        assertThat(user.getEmailVerified()).isTrue();
        assertThat(token.getConsumedAt()).isNotNull();
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), any());

        assertThatThrownBy(() -> service.verify(raw))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void expiredTokenIsRejected() {
        String raw = "expired-token";
        EmailVerificationToken token = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(EmailVerificationService.hash(raw))
                .emailSnapshot(user.getEmail())
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .build();
        when(tokenRepository.findByTokenHash(token.getTokenHash())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verify(raw))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("invalid or expired");
        verify(userRepository, never()).save(any());
    }

    @Test
    void resendForUnknownEmailHasGenericNoOpBehavior() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com"))
                .thenReturn(Optional.empty());

        service.resend(" Missing@Example.com ");

        verifyNoInteractions(emailSender);
        verify(tokenRepository, never()).save(any());
    }
}
