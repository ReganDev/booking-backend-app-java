package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.EmailVerificationToken;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.EmailDeliveryException;
import com.dev.bookingapp.javabookingapp.repository.EmailVerificationTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.RefreshTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ResendEmailSender emailSender;

    @Value("${app.email-verification.expiry:24h}")
    private Duration expiry;

    @Value("${app.email-verification.resend-interval:60s}")
    private Duration resendInterval;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Transactional
    public void issueFor(User user) {
        if (!requiresVerification(user) || Boolean.TRUE.equals(user.getEmailVerified())) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        tokenRepository.revokeActiveByUserId(user.getId(), now);

        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokenRepository.save(EmailVerificationToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .emailSnapshot(user.getEmail())
                .expiresAt(now.plus(expiry))
                .build());

        sendRequired(user, rawToken);
    }

    @Transactional
    public void resend(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            if (!requiresVerification(user) || Boolean.TRUE.equals(user.getEmailVerified())) {
                return;
            }

            OffsetDateTime cutoff = OffsetDateTime.now().minus(resendInterval);
            boolean rateLimited = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                    .map(token -> token.getCreatedAt() != null
                            && token.getCreatedAt().isAfter(cutoff))
                    .orElse(false);
            if (!rateLimited) {
                try {
                    issueFor(user);
                } catch (EmailDeliveryException ex) {
                    // The public resend response stays generic so callers
                    // cannot discover whether an account exists.
                    log.error("Could not resend verification email for user {}", user.getId(), ex);
                }
            }
        });
    }

    @Transactional
    public void verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }

        EmailVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(this::invalidToken);
        OffsetDateTime now = OffsetDateTime.now();

        if (!token.isUsableAt(now)
                || !normalizeEmail(token.getEmailSnapshot())
                    .equals(normalizeEmail(token.getUser().getEmail()))) {
            throw invalidToken();
        }

        User user = token.getUser();
        token.setConsumedAt(now);
        user.setEmailVerified(true);
        tokenRepository.save(token);
        userRepository.save(user);
        refreshTokenRepository.revokeAllByUserId(user.getId(), now);
    }

    private void sendRequired(User user, String rawToken) {
        if (!emailSender.isConfigured()) {
            throw new EmailDeliveryException(
                    "Verification email delivery is not configured"
            );
        }
        String baseUrl = frontendUrl.replaceAll("/+$", "");
        String link = baseUrl + "/verify-email?token=" + rawToken;
        try {
            emailSender.send(
                    "BookingBase",
                    user.getEmail(),
                    null,
                    "Verify your email address",
                    "Hello " + user.getFirstName() + ",\n\nVerify your email address:\n"
                            + link + "\n\nThis link expires in " + expiry.toHours() + " hours.");
        } catch (RuntimeException ex) {
            log.error("Could not send verification email for user {}", user.getId(), ex);
            throw new EmailDeliveryException(
                    "Verification email could not be sent. Please try again.",
                    ex
            );
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean requiresVerification(User user) {
        return user.getRole() == UserRole.OWNER || user.getRole() == UserRole.CUSTOMER;
    }

    static String hash(String token) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private BadRequestException invalidToken() {
        return new BadRequestException("Verification token is invalid or expired");
    }
}
