package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingStartRequest;
import com.dev.bookingapp.javabookingapp.dto.response.GuestBookingStartResponse;
import com.dev.bookingapp.javabookingapp.entity.BookingOtpSession;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Service;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.EmailDeliveryException;
import com.dev.bookingapp.javabookingapp.repository.BookingOtpSessionRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class GuestBookingService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BookingOtpSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final BusinessService businessService;
    private final ServiceService serviceService;
    private final AvailabilityService availabilityService;
    private final BookingService bookingService;
    private final PasswordResetService passwordResetService;
    private final ResendEmailSender emailSender;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.booking-otp.expiry:10m}")
    private Duration expiry;

    @Value("${app.booking-otp.resend-interval:60s}")
    private Duration resendInterval;

    @Transactional
    public GuestBookingStartResponse start(GuestBookingStartRequest request) {
        Business business = businessService.getEntityById(request.getBusinessId());
        if (!Boolean.TRUE.equals(business.getIsActive())) {
            throw new BadRequestException("This business is not currently accepting bookings");
        }

        Service bookedService = serviceService.getEntityById(request.getServiceId());
        if (!bookedService.getBusiness().getId().equals(business.getId())) {
            throw new BadRequestException("Service does not belong to this business");
        }
        if (!Boolean.TRUE.equals(bookedService.getIsActive())) {
            throw new BadRequestException("This service is not available to book");
        }

        // Reject anything that isn't an open slot before sending any email
        availabilityService.ensureSlotAvailable(business, bookedService, request.getStartDatetime());

        String email = EmailVerificationService.normalizeEmail(request.getEmail());
        boolean newAccount = false;
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            newAccount = true;
            // Passwordless account: an unguessable hash the user can replace
            // later via the claim link. password_hash is NOT NULL in the schema.
            byte[] randomPassword = new byte[32];
            SECURE_RANDOM.nextBytes(randomPassword);
            user = userRepository.save(User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(
                            Base64.getUrlEncoder().withoutPadding().encodeToString(randomPassword)))
                    .firstName(request.getFirstName().trim())
                    .lastName(request.getLastName().trim())
                    .phone(request.getPhone())
                    .role(UserRole.CUSTOMER)
                    .isActive(true)
                    .emailVerified(false)
                    .build());
        } else {
            if (user.getRole() != UserRole.CUSTOMER) {
                throw new BadRequestException(
                        "This email belongs to a business account. Please sign in to book.");
            }
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                throw new BadRequestException("This account is not active");
            }
            // One code per minute per email, across sessions
            OffsetDateTime cutoff = OffsetDateTime.now().minus(resendInterval);
            boolean rateLimited = sessionRepository
                    .findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                    .map(s -> s.getCreatedAt() != null && s.getCreatedAt().isAfter(cutoff))
                    .orElse(false);
            if (rateLimited) {
                throw new BadRequestException(
                        "A code was sent recently. Please wait a minute before trying again.");
            }
        }

        String code = generateCode();
        OffsetDateTime now = OffsetDateTime.now();
        BookingOtpSession session = sessionRepository.save(BookingOtpSession.builder()
                .user(user)
                .business(business)
                .service(bookedService)
                .startDatetime(request.getStartDatetime())
                .customerNotes(request.getCustomerNotes())
                .emailReminder(Boolean.TRUE.equals(request.getEmailReminder()))
                .smsReminder(Boolean.TRUE.equals(request.getSmsReminder()))
                .newAccount(newAccount)
                .codeHash(EmailVerificationService.hash(code))
                .lastSentAt(now)
                .expiresAt(now.plus(expiry))
                .build());

        sendCode(user, code);
        return new GuestBookingStartResponse(session.getId(), session.getExpiresAt());
    }

    private String generateCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private void sendCode(User user, String code) {
        if (!emailSender.isConfigured()) {
            throw new EmailDeliveryException("Booking code delivery is not configured");
        }
        try {
            emailSender.send(
                    "BookingBase",
                    user.getEmail(),
                    null,
                    "Your booking confirmation code",
                    "Hello " + user.getFirstName() + ",\n\nYour booking confirmation code is:\n\n"
                            + code + "\n\nIt expires in " + expiry.toMinutes()
                            + " minutes. If you did not request this, you can ignore this email.");
        } catch (RuntimeException ex) {
            log.error("Could not send booking code for user {}", user.getId(), ex);
            throw new EmailDeliveryException(
                    "We could not send your code. Please try again.", ex);
        }
    }
}
