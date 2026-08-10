package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.PasswordResetToken;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.repository.PasswordResetTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.RefreshTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ResendEmailSender emailSender;

    @Value("${app.password-reset.expiry:1h}")
    private Duration expiry;

    @Value("${app.password-reset.claim-expiry:24h}")
    private Duration claimExpiry;

    @Value("${app.password-reset.resend-interval:60s}")
    private Duration resendInterval;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /** Issues a reset link for the given address. Callers must return the same
     *  generic response whether or not an account exists. */
    @Transactional
    public void requestReset(String rawEmail) {
        String email = EmailVerificationService.normalizeEmail(rawEmail);
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                return;
            }

            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime cutoff = now.minus(resendInterval);
            boolean rateLimited = tokenRepository
                    .findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                    .map(token -> token.getCreatedAt() != null
                            && token.getCreatedAt().isAfter(cutoff))
                    .orElse(false);
            if (rateLimited) {
                return;
            }

            tokenRepository.revokeActiveByUserId(user.getId(), now);

            byte[] bytes = new byte[32];
            SECURE_RANDOM.nextBytes(bytes);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

            tokenRepository.save(PasswordResetToken.builder()
                    .user(user)
                    .tokenHash(EmailVerificationService.hash(rawToken))
                    .expiresAt(now.plus(expiry))
                    .build());

            try {
                send(user, rawToken);
            } catch (RuntimeException ex) {
                // The public response stays generic so callers cannot discover
                // whether an account exists.
                log.error("Could not send password reset email for user {}", user.getId(), ex);
            }
        });
    }

    /** Sends a "set a password" link after a guest OTP booking. Best-effort:
     *  the booking must never fail because this email could not be sent. */
    @Transactional
    public void issueClaimLink(User user) {
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        tokenRepository.revokeActiveByUserId(user.getId(), now);

        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokenRepository.save(PasswordResetToken.builder()
                .user(user)
                .tokenHash(EmailVerificationService.hash(rawToken))
                .expiresAt(now.plus(claimExpiry))
                .build());

        if (!emailSender.isConfigured()) {
            log.error("Claim-account email delivery is not configured");
            return;
        }
        String baseUrl = frontendUrl.replaceAll("/+$", "");
        String link = baseUrl + "/reset-password?token=" + rawToken;
        try {
            emailSender.send(
                    "BookingBase",
                    user.getEmail(),
                    null,
                    "Manage your bookings on BookingBase",
                    "Hello " + user.getFirstName() + ",\n\nYour booking is confirmed."
                            + " Set a password to view and manage your bookings any time:\n"
                            + link + "\n\nThis link expires in " + claimExpiry.toHours()
                            + " hours. If you did not book with us, you can ignore this email.");
        } catch (RuntimeException ex) {
            log.error("Could not send claim-account email for user {}", user.getId(), ex);
        }
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }

        PasswordResetToken token = tokenRepository
                .findByTokenHash(EmailVerificationService.hash(rawToken))
                .orElseThrow(this::invalidToken);
        OffsetDateTime now = OffsetDateTime.now();

        if (!token.isUsableAt(now)) {
            throw invalidToken();
        }

        User user = token.getUser();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw invalidToken();
        }

        token.setConsumedAt(now);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        tokenRepository.save(token);
        userRepository.save(user);
        // Force every existing session to sign in again with the new password.
        refreshTokenRepository.revokeAllByUserId(user.getId(), now);
    }

    private void send(User user, String rawToken) {
        if (!emailSender.isConfigured()) {
            log.error("Password reset email delivery is not configured");
            return;
        }
        String baseUrl = frontendUrl.replaceAll("/+$", "");
        String link = baseUrl + "/reset-password?token=" + rawToken;
        emailSender.send(
                "BookingBase",
                user.getEmail(),
                null,
                "Reset your password",
                "Hello " + user.getFirstName() + ",\n\nReset your password:\n"
                        + link + "\n\nThis link expires in " + expiry.toMinutes()
                        + " minutes. If you did not request this, you can ignore this email.");
    }

    private BadRequestException invalidToken() {
        return new BadRequestException("Password reset token is invalid or expired");
    }
}
