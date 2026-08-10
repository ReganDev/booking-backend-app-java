package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingStartRequest;
import com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class GuestBookingService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    static final int MAX_ATTEMPTS = 5;

    private final BookingOtpSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final BusinessService businessService;
    private final ServiceService serviceService;
    private final AvailabilityService availabilityService;
    private final BookingService bookingService;
    private final PasswordResetService passwordResetService;
    private final ResendEmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final BookingOtpAttemptRecorder attemptRecorder;

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

        // Reject a missing/invalid address for mobile-visit services and
        // anything that isn't an open slot before sending any email
        BookingService.validateCustomerAddress(bookedService, request.getAddressLine1(),
                request.getAddressLine2(), request.getAddressCity(), request.getAddressPostcode());
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
                .addressLine1(request.getAddressLine1())
                .addressLine2(request.getAddressLine2())
                .addressCity(request.getAddressCity())
                .addressPostcode(BookingService.normalizePostcode(request.getAddressPostcode()))
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

    @Transactional
    public BookingResponse verify(UUID bookingSessionId, String code) {
        BookingOtpSession session = sessionRepository.findById(bookingSessionId)
                .orElseThrow(this::invalidCode);
        OffsetDateTime now = OffsetDateTime.now();

        if (!session.isUsableAt(now) || session.getAttempts() >= MAX_ATTEMPTS) {
            throw invalidCode();
        }

        boolean matches = MessageDigest.isEqual(
                session.getCodeHash().getBytes(StandardCharsets.UTF_8),
                EmailVerificationService.hash(code).getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            attemptRecorder.recordFailedAttempt(session.getId());
            throw new BadRequestException("Incorrect code. Please try again.");
        }

        session.setConsumedAt(now);
        sessionRepository.save(session);

        // The code proves ownership of the email address
        User user = session.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        PublicBookingRequest bookingRequest = new PublicBookingRequest();
        bookingRequest.setServiceId(session.getService().getId());
        bookingRequest.setStartDatetime(session.getStartDatetime());
        bookingRequest.setCustomerNotes(session.getCustomerNotes());
        bookingRequest.setEmailReminder(session.getEmailReminder());
        bookingRequest.setSmsReminder(session.getSmsReminder());
        bookingRequest.setAddressLine1(session.getAddressLine1());
        bookingRequest.setAddressLine2(session.getAddressLine2());
        bookingRequest.setAddressCity(session.getAddressCity());
        bookingRequest.setAddressPostcode(session.getAddressPostcode());

        // Re-validates the slot and sends the booking-details email
        BookingResponse created = bookingService.createPublicBooking(
                session.getBusiness().getId(), bookingRequest, user.getId());

        if (Boolean.TRUE.equals(session.getNewAccount())) {
            // Best-effort: issueClaimLink never throws on email failure
            passwordResetService.issueClaimLink(user);
        }
        return created;
    }

    @Transactional
    public void resend(UUID bookingSessionId) {
        sessionRepository.findById(bookingSessionId).ifPresent(session -> {
            OffsetDateTime now = OffsetDateTime.now();
            if (!session.isUsableAt(now) || session.getAttempts() >= MAX_ATTEMPTS) {
                return;
            }
            if (session.getLastSentAt() != null
                    && session.getLastSentAt().isAfter(now.minus(resendInterval))) {
                return;
            }
            String code = generateCode();
            session.setCodeHash(EmailVerificationService.hash(code));
            session.setLastSentAt(now);
            sessionRepository.save(session);
            try {
                sendCode(session.getUser(), code);
            } catch (RuntimeException ex) {
                // The public resend response stays generic
                log.error("Could not resend booking code for session {}", session.getId(), ex);
            }
        });
    }

    private BadRequestException invalidCode() {
        return new BadRequestException("This code is invalid or has expired. Request a new one.");
    }
}
