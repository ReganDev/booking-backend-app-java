package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.BookingManageToken;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.repository.BookingManageTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Issues and resolves the per-booking manage-link tokens carried in booking
 * emails. Tokens are stored hashed (same pattern as password reset) and stay
 * valid until the appointment ends — validity is derived from the booking,
 * so rescheduling never needs to touch the token row.
 */
@Service
@RequiredArgsConstructor
public class BookingManageTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BookingManageTokenRepository tokenRepository;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /** Issues (or replaces) the manage token for a booking; returns the full link. */
    @Transactional
    public String issueLink(Booking booking) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        BookingManageToken token = tokenRepository.findByBookingId(booking.getId())
                .orElseGet(() -> BookingManageToken.builder().booking(booking).build());
        token.setTokenHash(EmailVerificationService.hash(rawToken));
        tokenRepository.save(token);

        String baseUrl = frontendUrl.replaceAll("/+$", "");
        return baseUrl + "/manage/booking/" + rawToken;
    }

    /** Resolves a raw token to its booking. Links die when the appointment ends. */
    @Transactional(readOnly = true)
    public Booking resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidLink();
        }
        BookingManageToken token = tokenRepository
                .findByTokenHash(EmailVerificationService.hash(rawToken))
                .orElseThrow(this::invalidLink);
        Booking booking = token.getBooking();
        if (booking.getEndDatetime() == null
                || !booking.getEndDatetime().isAfter(OffsetDateTime.now())) {
            throw invalidLink();
        }
        return booking;
    }

    private BadRequestException invalidLink() {
        return new BadRequestException("This manage link is invalid or has expired");
    }
}
