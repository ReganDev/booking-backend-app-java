package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.PasswordResetToken;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.repository.PasswordResetTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.RefreshTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock PasswordResetTokenRepository tokenRepository;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ResendEmailSender emailSender;
    @InjectMocks PasswordResetService service;

    private User user;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expiry", Duration.ofHours(1));
        ReflectionTestUtils.setField(service, "claimExpiry", Duration.ofHours(24));
        ReflectionTestUtils.setField(service, "resendInterval", Duration.ofMinutes(1));
        ReflectionTestUtils.setField(service, "frontendUrl", "https://app.example.com/");
        user = User.builder()
                .id(UUID.randomUUID())
                .email("guest@example.com")
                .firstName("Gwen")
                .lastName("Guest")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(true)
                .build();
    }

    @Test
    void claimLinkStoresHashedTokenAndSendsTailoredEmail() {
        when(emailSender.isConfigured()).thenReturn(true);

        service.issueClaimLink(user);

        ArgumentCaptor<PasswordResetToken> captor =
                ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).revokeActiveByUserId(eq(user.getId()), any());
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).matches("[0-9a-f]{64}");
        verify(emailSender).send(
                eq("BookingBase"),
                eq(user.getEmail()),
                isNull(),
                eq("Manage your bookings on BookingBase"),
                contains("https://app.example.com/reset-password?token="));
    }

    @Test
    void claimLinkNeverThrowsWhenEmailFails() {
        when(emailSender.isConfigured()).thenReturn(true);
        doThrow(new RuntimeException("resend down"))
                .when(emailSender).send(any(), any(), any(), any(), any());

        service.issueClaimLink(user); // must not throw

        verify(tokenRepository).save(any(PasswordResetToken.class));
    }
}
