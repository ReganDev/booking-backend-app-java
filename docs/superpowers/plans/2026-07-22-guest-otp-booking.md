# Guest OTP Booking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a customer with no account complete a booking by proving their email with a 6-digit code on the booking page — no password, no page exit.

**Architecture:** A new `booking_otp_sessions` table stores the pending booking payload plus a hashed 6-digit code. `POST /public/bookings/start` validates the slot, creates or loads a passwordless CUSTOMER user, and emails the code; `POST /public/bookings/verify` checks the code, marks the user verified, and creates the booking through the existing `BookingService.createPublicBooking` path. The frontend replaces the step-4 sign-in wall with a details form + inline code input for guests, keeping the signed-in path unchanged.

**Tech Stack:** Java 21 / Spring Boot 4 (backend repo `booking-backend-app-java/javabookingapp`), Flyway/PostgreSQL, Resend for email, React 19 + TypeScript + Vitest (frontend repo `bookingsystem-frontend`).

## Global Constraints

- Spec: `booking-backend-app-java/docs/superpowers/specs/2026-07-22-booking-conversion-design.md`
- OTP: 6 digits; expiry 10 minutes; max 5 attempts; resend at most once per 60s.
- Codes and tokens are stored **hashed** (SHA-256 hex via `EmailVerificationService.hash`) — never plaintext.
- Emails are normalized with `EmailVerificationService.normalizeEmail` before any lookup or persist; `ux_users_email_lower` guarantees one account per email.
- New public endpoints live under `/api/v1/public/**` (already `permitAll` in `SecurityConfig`) — do NOT touch `SecurityConfig`.
- The existing authenticated booking endpoint and its `@PreAuthorize` gate stay untouched.
- Backend tests: JUnit 5 + Mockito + AssertJ, mirroring `EmailVerificationServiceTest`. Frontend tests: Vitest + Testing Library, mirroring `BookBusinessPage.test.tsx`.
- All backend paths below are relative to `booking-backend-app-java/javabookingapp/`; all frontend paths relative to `bookingsystem-frontend/`.
- Run backend tests with `./mvnw -q test`; frontend with `npm test`.
- Commit after every task (both repos have clean trees; commit in whichever repo the task touches).

---

### Task 1: Migration + entity + repository for OTP sessions

**Files:**
- Create: `src/main/resources/db/migration/V9__booking_otp_sessions.sql`
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/entity/BookingOtpSession.java`
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/repository/BookingOtpSessionRepository.java`

**Interfaces:**
- Consumes: `User`, `Business`, `Service` entities; `EmailVerificationToken` as the pattern to mirror.
- Produces: `BookingOtpSession` entity (builder fields: `user`, `business`, `service`, `startDatetime`, `customerNotes`, `emailReminder`, `smsReminder`, `newAccount`, `codeHash`, `attempts`, `lastSentAt`, `expiresAt`, `consumedAt`; method `isUsableAt(OffsetDateTime)`), `BookingOtpSessionRepository extends JpaRepository<BookingOtpSession, UUID>` with `Optional<BookingOtpSession> findFirstByUserIdOrderByCreatedAtDesc(UUID userId)`.

- [ ] **Step 1: Write the migration**

```sql
-- V9__booking_otp_sessions.sql
-- Pending guest bookings awaiting email OTP confirmation.
CREATE TABLE booking_otp_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,

    -- Booking payload, applied when the code is verified
    start_datetime TIMESTAMP WITH TIME ZONE NOT NULL,
    customer_notes TEXT,
    email_reminder BOOLEAN NOT NULL DEFAULT TRUE,
    sms_reminder BOOLEAN NOT NULL DEFAULT FALSE,

    -- True when this flow created the user; drives the claim-account email
    new_account BOOLEAN NOT NULL DEFAULT FALSE,

    -- OTP state
    code_hash CHAR(64) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_booking_otp_sessions_user
    ON booking_otp_sessions(user_id, created_at DESC);
```

- [ ] **Step 2: Write the entity**

```java
package com.dev.bookingapp.javabookingapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "booking_otp_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingOtpSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @Column(name = "start_datetime", nullable = false)
    private OffsetDateTime startDatetime;

    @Column(name = "customer_notes")
    private String customerNotes;

    @Builder.Default
    @Column(name = "email_reminder", nullable = false)
    private Boolean emailReminder = true;

    @Builder.Default
    @Column(name = "sms_reminder", nullable = false)
    private Boolean smsReminder = false;

    @Builder.Default
    @Column(name = "new_account", nullable = false)
    private Boolean newAccount = false;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Builder.Default
    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_sent_at", nullable = false)
    private OffsetDateTime lastSentAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean isUsableAt(OffsetDateTime now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }
}
```

- [ ] **Step 3: Write the repository**

```java
package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.BookingOtpSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BookingOtpSessionRepository extends JpaRepository<BookingOtpSession, UUID> {

    Optional<BookingOtpSession> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS (no test yet — schema/entity only; behaviour is tested through the service tasks).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V9__booking_otp_sessions.sql \
        src/main/java/com/dev/bookingapp/javabookingapp/entity/BookingOtpSession.java \
        src/main/java/com/dev/bookingapp/javabookingapp/repository/BookingOtpSessionRepository.java
git commit -m "feat: booking OTP session table, entity, repository"
```

---

### Task 2: Claim-account email on PasswordResetService

**Files:**
- Modify: `src/main/java/com/dev/bookingapp/javabookingapp/service/PasswordResetService.java`
- Test: `src/test/java/com/dev/bookingapp/javabookingapp/service/PasswordResetServiceTest.java` (create if absent; otherwise extend)

**Interfaces:**
- Consumes: existing `PasswordResetTokenRepository`, `ResendEmailSender`, `EmailVerificationService.hash/normalizeEmail`.
- Produces: `public void issueClaimLink(User user)` — best-effort (never throws on email failure); issues a standard password-reset token so the existing `/reset-password` page works unchanged.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=PasswordResetServiceTest`
Expected: COMPILATION ERROR — `issueClaimLink` and `claimExpiry` do not exist.

- [ ] **Step 3: Implement issueClaimLink**

Add to `PasswordResetService` (below the existing `@Value` fields and after `requestReset`):

```java
    @Value("${app.password-reset.claim-expiry:24h}")
    private Duration claimExpiry;

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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=PasswordResetServiceTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dev/bookingapp/javabookingapp/service/PasswordResetService.java \
        src/test/java/com/dev/bookingapp/javabookingapp/service/PasswordResetServiceTest.java
git commit -m "feat: claim-account email for guest bookers"
```

---

### Task 3: GuestBookingService.start — validate, create user, send code

**Files:**
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/service/GuestBookingService.java`
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/dto/request/GuestBookingStartRequest.java`
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/dto/response/GuestBookingStartResponse.java`
- Test: `src/test/java/com/dev/bookingapp/javabookingapp/service/GuestBookingServiceTest.java`

**Interfaces:**
- Consumes: `BookingOtpSessionRepository` (Task 1), `UserRepository.findByEmailIgnoreCase`, `BusinessService.getEntityById`, `ServiceService.getEntityById`, `AvailabilityService.ensureSlotAvailable(Business, Service, OffsetDateTime)`, `ResendEmailSender`, `PasswordEncoder`, `EmailVerificationService.hash/normalizeEmail`.
- Produces: `GuestBookingStartResponse start(GuestBookingStartRequest request)` returning `{ bookingSessionId: UUID, expiresAt: OffsetDateTime }`. Task 4 adds `verify`/`resend` to this same class; Task 5 calls all three from the controller.

- [ ] **Step 1: Write the DTOs**

```java
package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class GuestBookingStartRequest {

    @NotNull(message = "Business ID is required")
    private UUID businessId;

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    private String phone;

    @NotNull(message = "Service ID is required")
    private UUID serviceId;

    @NotNull(message = "Start date/time is required")
    private OffsetDateTime startDatetime;

    private String customerNotes;

    private Boolean emailReminder;

    private Boolean smsReminder;
}
```

```java
package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
public class GuestBookingStartResponse {
    private UUID bookingSessionId;
    private OffsetDateTime expiresAt;
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.GuestBookingStartRequest;
import com.dev.bookingapp.javabookingapp.dto.response.GuestBookingStartResponse;
import com.dev.bookingapp.javabookingapp.entity.BookingOtpSession;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Service;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.repository.BookingOtpSessionRepository;
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
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuestBookingServiceTest {

    @Mock BookingOtpSessionRepository sessionRepository;
    @Mock UserRepository userRepository;
    @Mock BusinessService businessService;
    @Mock ServiceService serviceService;
    @Mock AvailabilityService availabilityService;
    @Mock BookingService bookingService;
    @Mock PasswordResetService passwordResetService;
    @Mock ResendEmailSender emailSender;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks GuestBookingService service;

    private Business business;
    private Service bookableService;
    private GuestBookingStartRequest startRequest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "expiry", Duration.ofMinutes(10));
        ReflectionTestUtils.setField(service, "resendInterval", Duration.ofSeconds(60));

        UUID businessId = UUID.randomUUID();
        business = Business.builder().id(businessId).name("Fab Hair").isActive(true).build();
        bookableService = Service.builder()
                .id(UUID.randomUUID())
                .business(business)
                .name("Haircut")
                .durationMinutes(30)
                .isActive(true)
                .build();

        startRequest = new GuestBookingStartRequest();
        startRequest.setBusinessId(businessId);
        startRequest.setFirstName("Gwen");
        startRequest.setLastName("Guest");
        startRequest.setEmail("  Gwen@Example.com ");
        startRequest.setServiceId(bookableService.getId());
        startRequest.setStartDatetime(OffsetDateTime.now().plusDays(2));
        startRequest.setEmailReminder(true);

        lenient().when(businessService.getEntityById(businessId)).thenReturn(business);
        lenient().when(serviceService.getEntityById(bookableService.getId()))
                .thenReturn(bookableService);
        lenient().when(emailSender.isConfigured()).thenReturn(true);
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$placeholder");
        lenient().when(sessionRepository.save(any(BookingOtpSession.class)))
                .thenAnswer(inv -> {
                    BookingOtpSession s = inv.getArgument(0);
                    if (s.getId() == null) s.setId(UUID.randomUUID());
                    return s;
                });
        lenient().when(userRepository.save(any(User.class)))
                .thenAnswer(inv -> {
                    User u = inv.getArgument(0);
                    if (u.getId() == null) u.setId(UUID.randomUUID());
                    return u;
                });
    }

    @Test
    void startCreatesPasswordlessCustomerAndHashedCodeForNewEmail() {
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.empty());

        GuestBookingStartResponse response = service.start(startRequest);

        assertThat(response.getBookingSessionId()).isNotNull();
        assertThat(response.getExpiresAt())
                .isAfter(OffsetDateTime.now().plusMinutes(9));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User created = userCaptor.getValue();
        assertThat(created.getEmail()).isEqualTo("gwen@example.com");
        assertThat(created.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(created.getEmailVerified()).isFalse();
        assertThat(created.getPasswordHash()).isEqualTo("$2a$10$placeholder");

        ArgumentCaptor<BookingOtpSession> sessionCaptor =
                ArgumentCaptor.forClass(BookingOtpSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        BookingOtpSession session = sessionCaptor.getValue();
        assertThat(session.getCodeHash()).matches("[0-9a-f]{64}");
        assertThat(session.getNewAccount()).isTrue();

        verify(availabilityService).ensureSlotAvailable(
                business, bookableService, startRequest.getStartDatetime());
        verify(emailSender).send(eq("BookingBase"), eq("gwen@example.com"),
                isNull(), contains("code"), matches("(?s).*\\b\\d{6}\\b.*"));
    }

    @Test
    void startReusesExistingCustomerAccountWithoutOverwritingIt() {
        User existing = User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwendolyn")
                .lastName("Original")
                .passwordHash("$existing")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(true)
                .build();
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.of(existing));

        service.start(startRequest);

        verify(userRepository, never()).save(any());
        ArgumentCaptor<BookingOtpSession> captor =
                ArgumentCaptor.forClass(BookingOtpSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getNewAccount()).isFalse();
        assertThat(captor.getValue().getUser()).isSameAs(existing);
    }

    @Test
    void startRejectsBusinessAccountEmails() {
        User owner = User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwen").lastName("Owner")
                .passwordHash("$existing")
                .role(UserRole.OWNER)
                .isActive(true)
                .build();
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.start(startRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("business account");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void startRateLimitsRepeatRequestsForTheSameEmail() {
        User existing = User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwen").lastName("Guest")
                .passwordHash("$existing")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .build();
        when(userRepository.findByEmailIgnoreCase("gwen@example.com"))
                .thenReturn(Optional.of(existing));
        BookingOtpSession recent = BookingOtpSession.builder()
                .user(existing)
                .codeHash("x".repeat(64))
                .lastSentAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        ReflectionTestUtils.setField(recent, "createdAt", OffsetDateTime.now());
        when(sessionRepository.findFirstByUserIdOrderByCreatedAtDesc(existing.getId()))
                .thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> service.start(startRequest))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("wait");
        verify(emailSender, never()).send(any(), any(), any(), any(), any());
    }
}
```

Note: `BookingOtpSession.createdAt` is set by Hibernate at persist time, so tests set it with `ReflectionTestUtils.setField`.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=GuestBookingServiceTest`
Expected: COMPILATION ERROR — `GuestBookingService` does not exist.

- [ ] **Step 4: Implement GuestBookingService with start()**

```java
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -q test -Dtest=GuestBookingServiceTest`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/dev/bookingapp/javabookingapp/service/GuestBookingService.java \
        src/main/java/com/dev/bookingapp/javabookingapp/dto/request/GuestBookingStartRequest.java \
        src/main/java/com/dev/bookingapp/javabookingapp/dto/response/GuestBookingStartResponse.java \
        src/test/java/com/dev/bookingapp/javabookingapp/service/GuestBookingServiceTest.java
git commit -m "feat: guest booking start - OTP session creation and code email"
```

---

### Task 4: GuestBookingService.verify and resend

**Files:**
- Modify: `src/main/java/com/dev/bookingapp/javabookingapp/service/GuestBookingService.java`
- Test: `src/test/java/com/dev/bookingapp/javabookingapp/service/GuestBookingServiceTest.java` (extend)

**Interfaces:**
- Consumes: `BookingService.createPublicBooking(UUID businessId, PublicBookingRequest request, UUID authenticatedUserId)` — already re-validates the slot and sends the booking-details email; `PasswordResetService.issueClaimLink(User)` (Task 2).
- Produces: `BookingResponse verify(UUID bookingSessionId, String code)`; `void resend(UUID bookingSessionId)`. Constant `MAX_ATTEMPTS = 5`.

- [ ] **Step 1: Write the failing tests (append to GuestBookingServiceTest)**

```java
    // --- verify ---

    private BookingOtpSession usableSession(User user, String code) {
        BookingOtpSession session = BookingOtpSession.builder()
                .id(UUID.randomUUID())
                .user(user)
                .business(business)
                .service(bookableService)
                .startDatetime(startRequest.getStartDatetime())
                .emailReminder(true)
                .smsReminder(false)
                .newAccount(true)
                .codeHash(EmailVerificationService.hash(code))
                .attempts(0)
                .lastSentAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusMinutes(10))
                .build();
        lenient().when(sessionRepository.findById(session.getId()))
                .thenReturn(Optional.of(session));
        return session;
    }

    private User guestUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("gwen@example.com")
                .firstName("Gwen").lastName("Guest")
                .passwordHash("$hash")
                .role(UserRole.CUSTOMER)
                .isActive(true)
                .emailVerified(false)
                .build();
    }

    @Test
    void verifyMarksUserVerifiedCreatesBookingAndSendsClaimLink() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");
        BookingResponse booking = new BookingResponse();
        when(bookingService.createPublicBooking(
                eq(business.getId()), any(PublicBookingRequest.class), eq(user.getId())))
                .thenReturn(booking);

        BookingResponse result = service.verify(session.getId(), "123456");

        assertThat(result).isSameAs(booking);
        assertThat(user.getEmailVerified()).isTrue();
        assertThat(session.getConsumedAt()).isNotNull();
        verify(passwordResetService).issueClaimLink(user);
    }

    @Test
    void verifyDoesNotSendClaimLinkForExistingAccounts() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");
        session.setNewAccount(false);
        when(bookingService.createPublicBooking(any(), any(), any()))
                .thenReturn(new BookingResponse());

        service.verify(session.getId(), "123456");

        verify(passwordResetService, never()).issueClaimLink(any());
    }

    @Test
    void verifyWrongCodeIncrementsAttemptsAndRejects() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");

        assertThatThrownBy(() -> service.verify(session.getId(), "654321"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Incorrect code");
        assertThat(session.getAttempts()).isEqualTo(1);
        verify(bookingService, never()).createPublicBooking(any(), any(), any());
    }

    @Test
    void verifyRejectsAfterMaxAttempts() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");
        session.setAttempts(5);

        assertThatThrownBy(() -> service.verify(session.getId(), "123456"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("expired");
        verify(bookingService, never()).createPublicBooking(any(), any(), any());
    }

    @Test
    void verifyRejectsExpiredSessions() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");
        session.setExpiresAt(OffsetDateTime.now().minusMinutes(1));

        assertThatThrownBy(() -> service.verify(session.getId(), "123456"))
                .isInstanceOf(BadRequestException.class);
        verify(bookingService, never()).createPublicBooking(any(), any(), any());
    }

    // --- resend ---

    @Test
    void resendGeneratesNewCodeAfterInterval() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");
        session.setLastSentAt(OffsetDateTime.now().minusMinutes(2));
        String oldHash = session.getCodeHash();

        service.resend(session.getId());

        assertThat(session.getCodeHash()).isNotEqualTo(oldHash);
        verify(emailSender).send(eq("BookingBase"), eq(user.getEmail()),
                isNull(), any(), matches("(?s).*\\b\\d{6}\\b.*"));
    }

    @Test
    void resendIsSilentlyRateLimitedWithinInterval() {
        User user = guestUser();
        BookingOtpSession session = usableSession(user, "123456");
        session.setLastSentAt(OffsetDateTime.now());

        service.resend(session.getId());

        verify(emailSender, never()).send(any(), any(), any(), any(), any());
    }
```

Add these imports to the test file: `com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest`, `com.dev.bookingapp.javabookingapp.dto.response.BookingResponse`.

(If `BookingResponse` has no no-args constructor, instantiate it the way `BookingServiceTest` does — check that file and copy its construction pattern.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -q test -Dtest=GuestBookingServiceTest`
Expected: COMPILATION ERROR — `verify` and `resend` do not exist on the service.

- [ ] **Step 3: Implement verify() and resend()**

Add to `GuestBookingService`:

```java
    static final int MAX_ATTEMPTS = 5;

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
            session.setAttempts(session.getAttempts() + 1);
            sessionRepository.save(session);
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
```

New imports for the service: `com.dev.bookingapp.javabookingapp.dto.request.PublicBookingRequest`, `com.dev.bookingapp.javabookingapp.dto.response.BookingResponse`, `java.nio.charset.StandardCharsets`, `java.security.MessageDigest`, `java.util.UUID`.

- [ ] **Step 4: Run the full backend suite**

Run: `./mvnw -q test`
Expected: PASS — all tests, including the 11 in `GuestBookingServiceTest`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dev/bookingapp/javabookingapp/service/GuestBookingService.java \
        src/test/java/com/dev/bookingapp/javabookingapp/service/GuestBookingServiceTest.java
git commit -m "feat: guest booking verify and resend with attempt limits"
```

---

### Task 5: Public endpoints for start / verify / resend

**Files:**
- Modify: `src/main/java/com/dev/bookingapp/javabookingapp/controller/PublicController.java`
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/dto/request/GuestBookingVerifyRequest.java`
- Create: `src/main/java/com/dev/bookingapp/javabookingapp/dto/request/GuestBookingResendRequest.java`

**Interfaces:**
- Consumes: `GuestBookingService.start/verify/resend` (Tasks 3–4).
- Produces: `POST /api/v1/public/bookings/start` → 200 `GuestBookingStartResponse`; `POST /api/v1/public/bookings/verify` → 201 `BookingResponse`; `POST /api/v1/public/bookings/resend` → 204. These are the wire contracts Task 6 consumes.

- [ ] **Step 1: Write the DTOs**

```java
package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

@Data
public class GuestBookingVerifyRequest {

    @NotNull(message = "Booking session ID is required")
    private UUID bookingSessionId;

    @NotBlank(message = "Code is required")
    @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
    private String code;
}
```

```java
package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class GuestBookingResendRequest {

    @NotNull(message = "Booking session ID is required")
    private UUID bookingSessionId;
}
```

- [ ] **Step 2: Add endpoints to PublicController**

Add the field `private final GuestBookingService guestBookingService;` to the constructor-injected fields, the imports (`GuestBookingStartRequest`, `GuestBookingVerifyRequest`, `GuestBookingResendRequest`, `GuestBookingStartResponse`, `GuestBookingService`), and these handlers:

```java
    @PostMapping("/bookings/start")
    public ResponseEntity<GuestBookingStartResponse> startGuestBooking(
            @Valid @RequestBody GuestBookingStartRequest request) {
        return ResponseEntity.ok(guestBookingService.start(request));
    }

    @PostMapping("/bookings/verify")
    public ResponseEntity<BookingResponse> verifyGuestBooking(
            @Valid @RequestBody GuestBookingVerifyRequest request) {
        BookingResponse created = guestBookingService.verify(
                request.getBookingSessionId(), request.getCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/bookings/resend")
    public ResponseEntity<Void> resendGuestBookingCode(
            @Valid @RequestBody GuestBookingResendRequest request) {
        guestBookingService.resend(request.getBookingSessionId());
        return ResponseEntity.noContent().build();
    }
```

- [ ] **Step 3: Verify compile + full suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, all tests pass. (There is no controller-test infrastructure in this repo; the service layer carries the behaviour tests. Do not add a new test framework in this task.)

- [ ] **Step 4: Smoke-test locally (optional but recommended)**

If a local dev DB is configured, start the app and exercise validation (expected 400 with field errors, proving the endpoint is wired):

Run: `curl -s -X POST localhost:8080/api/v1/public/bookings/start -H 'Content-Type: application/json' -d '{}' | head -c 300`
Expected: JSON error response mentioning "Business ID is required".

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dev/bookingapp/javabookingapp/controller/PublicController.java \
        src/main/java/com/dev/bookingapp/javabookingapp/dto/request/GuestBookingVerifyRequest.java \
        src/main/java/com/dev/bookingapp/javabookingapp/dto/request/GuestBookingResendRequest.java
git commit -m "feat: public guest booking endpoints (start/verify/resend)"
```

---

### Task 6: Frontend API functions + types

**Files:**
- Modify: `src/types/api.ts` (append types)
- Modify: `src/api/public.ts` (append functions)

**Interfaces:**
- Consumes: wire contracts from Task 5; existing `apiRequest` helper from `src/api/client.ts`.
- Produces: `startGuestBooking(request: GuestBookingStartRequest): Promise<GuestBookingStartResponse>`, `verifyGuestBooking(bookingSessionId: string, code: string): Promise<Booking>`, `resendGuestBookingCode(bookingSessionId: string): Promise<void>` — consumed by Task 7.

- [ ] **Step 1: Add types to `src/types/api.ts`**

```ts
export type GuestBookingStartRequest = {
  businessId: string
  firstName: string
  lastName: string
  email: string
  phone?: string
  serviceId: string
  startDatetime: string
  customerNotes?: string
  emailReminder: boolean
  smsReminder: boolean
}

export type GuestBookingStartResponse = {
  bookingSessionId: string
  expiresAt: string
}
```

- [ ] **Step 2: Add API functions to `src/api/public.ts`**

Extend the type import at the top with `GuestBookingStartRequest, GuestBookingStartResponse` and append:

```ts
export function startGuestBooking(request: GuestBookingStartRequest) {
  return apiRequest<GuestBookingStartResponse>('/public/bookings/start', {
    method: 'POST',
    body: request,
  })
}

export function verifyGuestBooking(bookingSessionId: string, code: string) {
  return apiRequest<Booking>('/public/bookings/verify', {
    method: 'POST',
    body: { bookingSessionId, code },
  })
}

export function resendGuestBookingCode(bookingSessionId: string) {
  return apiRequest<void>('/public/bookings/resend', {
    method: 'POST',
    body: { bookingSessionId },
  })
}
```

- [ ] **Step 3: Verify typecheck + existing tests**

Run: `npm run build && npm test`
Expected: tsc clean, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/types/api.ts src/api/public.ts
git commit -m "feat: guest booking API functions"
```

---

### Task 7: Guest details form + OTP input on BookBusinessPage

**Files:**
- Modify: `src/pages/BookBusinessPage.tsx`
- Test: `src/pages/BookBusinessPage.test.tsx` (extend)

**Interfaces:**
- Consumes: `startGuestBooking`, `verifyGuestBooking`, `resendGuestBookingCode` (Task 6); existing wizard state (`step`, `serviceId`, `selectedDate`, `selectedSlot`, `customer`, `customerNotes`, `emailReminder`, `smsReminder`); existing auth flags `isCustomer`, `isVerified` from `useAuth()`.
- Produces: step 4 renders the guest flow for non-signed-in visitors; existing signed-in verified flow unchanged. Confirmation reuses the existing `confirmation` state.

- [ ] **Step 1: Write the failing tests (append to BookBusinessPage.test.tsx)**

The existing file already has helpers `renderPage`, `chooseDay`, `slotNine`, and a `formatTime`-style label. Reuse them. Add:

```tsx
describe('guest booking with email code', () => {
  it('lets a guest book by entering a 6-digit emailed code', async () => {
    vi.mocked(publicApi.startGuestBooking).mockResolvedValue({
      bookingSessionId: 'sess-1',
      expiresAt: new Date(Date.now() + 10 * 60000).toISOString(),
    })
    const confirmed: Booking = {
      id: 'bk-1',
      businessId: business.id,
      customer: { id: 'c-1', firstName: 'Gwen', lastName: 'Guest', email: 'gwen@example.com' },
      service: haircut,
      startDatetime: slots[0].startDatetime,
      endDatetime: slots[0].endDatetime,
      status: 'PENDING',
    } as Booking
    vi.mocked(publicApi.verifyGuestBooking).mockResolvedValue(confirmed)

    const user = userEvent.setup()
    renderPage(false)

    await user.click(await screen.findByText('Haircut'))
    await chooseDay(user, slotNine)
    await user.click(
      screen.getByRole('button', {
        name: slotNine.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      }),
    )

    // Guest details form instead of a sign-in wall
    await user.type(screen.getByLabelText('First name'), 'Gwen')
    await user.type(screen.getByLabelText('Last name'), 'Guest')
    await user.type(screen.getByLabelText('Email'), 'gwen@example.com')
    await user.click(screen.getByRole('button', { name: /email me a code/i }))

    expect(publicApi.startGuestBooking).toHaveBeenCalledWith(
      expect.objectContaining({
        businessId: business.id,
        email: 'gwen@example.com',
        serviceId: haircut.id,
      }),
    )

    // Code entry appears with the email echoed back
    await screen.findByText(/we sent a code to/i)
    await user.type(screen.getByLabelText(/6-digit code/i), '123456')
    await user.click(screen.getByRole('button', { name: /confirm booking/i }))

    expect(publicApi.verifyGuestBooking).toHaveBeenCalledWith('sess-1', '123456')
    await screen.findByText(/thanks, gwen/i)
  })

  it('shows an error and keeps the code form on a wrong code', async () => {
    vi.mocked(publicApi.startGuestBooking).mockResolvedValue({
      bookingSessionId: 'sess-1',
      expiresAt: new Date(Date.now() + 10 * 60000).toISOString(),
    })
    vi.mocked(publicApi.verifyGuestBooking).mockRejectedValue(
      new ApiClientError(400, { message: 'Incorrect code. Please try again.' }),
    )

    const user = userEvent.setup()
    renderPage(false)

    await user.click(await screen.findByText('Haircut'))
    await chooseDay(user, slotNine)
    await user.click(
      screen.getByRole('button', {
        name: slotNine.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      }),
    )
    await user.type(screen.getByLabelText('First name'), 'Gwen')
    await user.type(screen.getByLabelText('Last name'), 'Guest')
    await user.type(screen.getByLabelText('Email'), 'gwen@example.com')
    await user.click(screen.getByRole('button', { name: /email me a code/i }))

    await screen.findByText(/we sent a code to/i)
    await user.type(screen.getByLabelText(/6-digit code/i), '000000')
    await user.click(screen.getByRole('button', { name: /confirm booking/i }))

    await screen.findByText(/incorrect code/i)
    expect(screen.getByLabelText(/6-digit code/i)).toBeInTheDocument()
  })

  it('still shows the prefilled one-click flow for verified customers', async () => {
    const user = userEvent.setup()
    renderPage(true)

    await user.click(await screen.findByText('Haircut'))
    await chooseDay(user, slotNine)
    await user.click(
      screen.getByRole('button', {
        name: slotNine.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      }),
    )

    expect(screen.getByLabelText('First name')).toHaveValue('Jane')
    expect(
      screen.getByRole('button', { name: /request appointment/i }),
    ).toBeInTheDocument()
    expect(publicApi.startGuestBooking).not.toHaveBeenCalled()
  })
})
```

Adjust the `ApiClientError` constructor call to match its real signature in `src/api/client.ts` (check the file; if it takes `(status, body, message)` or similar, mirror how `LoginPage.test.tsx` or `client.test.ts` constructs it). Import `ApiClientError` from `../api/client` at the top of the test file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `npm test`
Expected: the three new tests FAIL (guest form does not exist; step 4 renders the auth gate).

- [ ] **Step 3: Implement the guest flow in BookBusinessPage.tsx**

Add state near the other `useState` calls:

```tsx
  const [otpSession, setOtpSession] = useState<{
    id: string
    email: string
  } | null>(null)
  const [otpCode, setOtpCode] = useState('')
  const [resendCooldown, setResendCooldown] = useState(0)
```

Add a countdown effect (next to the other effects):

```tsx
  useEffect(() => {
    if (resendCooldown <= 0) return
    const timer = setInterval(
      () => setResendCooldown((s) => Math.max(0, s - 1)),
      1000,
    )
    return () => clearInterval(timer)
  }, [resendCooldown])
```

Add handlers (next to `handleSubmit`):

```tsx
  async function handleGuestStart(event: FormEvent) {
    event.preventDefault()
    if (!business || !selectedSlot) return
    setSubmitting(true)
    setError(null)
    try {
      const session = await publicApi.startGuestBooking({
        businessId: business.id,
        firstName: customer.firstName.trim(),
        lastName: customer.lastName.trim(),
        email: customer.email.trim(),
        phone: customer.phone || undefined,
        serviceId,
        startDatetime: selectedSlot,
        customerNotes: customerNotes || undefined,
        emailReminder,
        smsReminder,
      })
      setOtpSession({ id: session.bookingSessionId, email: customer.email.trim() })
      setOtpCode('')
      setResendCooldown(60)
    } catch (err) {
      const message =
        err instanceof ApiClientError
          ? getApiErrorMessage(err.status, err.body)
          : 'Unable to send your code. Please try again.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleGuestVerify(event: FormEvent) {
    event.preventDefault()
    if (!otpSession) return
    setSubmitting(true)
    setError(null)
    try {
      const booking = await publicApi.verifyGuestBooking(otpSession.id, otpCode)
      if (slug) sessionStorage.removeItem(`${DRAFT_PREFIX}${slug}`)
      setConfirmation(booking)
    } catch (err) {
      const message =
        err instanceof ApiClientError
          ? getApiErrorMessage(err.status, err.body)
          : 'Unable to confirm your booking. Please try again.'
      setError(message)
      if (err instanceof ApiClientError && err.status === 409) {
        // Slot taken while verifying: back to time selection
        setOtpSession(null)
        setStep(3)
        loadSlots()
      }
    } finally {
      setSubmitting(false)
    }
  }

  async function handleGuestResend() {
    if (!otpSession || resendCooldown > 0) return
    setResendCooldown(60)
    try {
      await publicApi.resendGuestBookingCode(otpSession.id)
    } catch {
      // Resend is best-effort; the cooldown still applies
    }
  }
```

Replace the step-4 auth-gate branch. The condition changes from `!isCustomer || !isVerified ?` (auth gate) to: signed-in verified customers keep the existing `<form ... onSubmit={handleSubmit}>` exactly as-is; everyone else gets the guest flow below (replacing the entire `<section className="auth-gate">` block):

```tsx
            {step === 4 && (
              isCustomer && isVerified ? (
                /* existing signed-in form, unchanged */
              ) : otpSession ? (
                <form className="form-grid booking-form" onSubmit={handleGuestVerify}>
                  <section className="form-section">
                    <h4>Check your email</h4>
                    <p className="slot-hint">
                      We sent a code to <strong>{otpSession.email}</strong>.
                      Enter it below to confirm your booking.
                    </p>
                    <div className="form-row">
                      <label htmlFor="otp-code">6-digit code</label>
                      <input
                        id="otp-code"
                        inputMode="numeric"
                        autoComplete="one-time-code"
                        pattern="\d{6}"
                        maxLength={6}
                        value={otpCode}
                        onChange={(e) =>
                          setOtpCode(e.target.value.replace(/\D/g, ''))
                        }
                        required
                        autoFocus
                      />
                    </div>
                    <div className="actions-row">
                      <button
                        className="btn btn-primary"
                        type="submit"
                        disabled={submitting || otpCode.length !== 6}
                      >
                        {submitting ? 'Confirming…' : 'Confirm booking'}
                      </button>
                      <button
                        type="button"
                        className="btn btn-secondary"
                        onClick={handleGuestResend}
                        disabled={resendCooldown > 0}
                      >
                        {resendCooldown > 0
                          ? `Resend code (${resendCooldown}s)`
                          : 'Resend code'}
                      </button>
                    </div>
                    <button
                      type="button"
                      className="btn-link"
                      onClick={() => setOtpSession(null)}
                    >
                      Wrong email? Edit your details
                    </button>
                  </section>
                </form>
              ) : (
                <form className="form-grid booking-form" onSubmit={handleGuestStart}>
                  <section className="form-section">
                    <h4>Your details</h4>
                    <p className="slot-hint">
                      No account needed — we&apos;ll email you a code to
                      confirm your booking.{' '}
                      <Link to={withReturnTo('/login', `/book/${slug}`)}>
                        Have an account? Sign in
                      </Link>
                    </p>
                    <div className="booking-name-fields">
                      <div className="form-row">
                        <label htmlFor="firstName">First name</label>
                        <input
                          id="firstName"
                          autoComplete="given-name"
                          value={customer.firstName}
                          onChange={(e) =>
                            setCustomer((c) => ({ ...c, firstName: e.target.value }))
                          }
                          required
                        />
                      </div>
                      <div className="form-row">
                        <label htmlFor="lastName">Last name</label>
                        <input
                          id="lastName"
                          autoComplete="family-name"
                          value={customer.lastName}
                          onChange={(e) =>
                            setCustomer((c) => ({ ...c, lastName: e.target.value }))
                          }
                          required
                        />
                      </div>
                    </div>
                    <div className="form-row">
                      <label htmlFor="email">Email</label>
                      <input
                        id="email"
                        type="email"
                        autoComplete="email"
                        value={customer.email}
                        onChange={(e) =>
                          setCustomer((c) => ({ ...c, email: e.target.value }))
                        }
                        required
                      />
                    </div>
                    <div className="form-row">
                      <label htmlFor="phone">Phone (optional)</label>
                      <input
                        id="phone"
                        type="tel"
                        autoComplete="tel"
                        value={customer.phone}
                        onChange={(e) =>
                          setCustomer((c) => ({ ...c, phone: e.target.value }))
                        }
                      />
                    </div>
                    <div className="form-row">
                      <label htmlFor="notes">Notes (optional)</label>
                      <textarea
                        id="notes"
                        rows={3}
                        value={customerNotes}
                        onChange={(e) => setCustomerNotes(e.target.value)}
                      />
                    </div>
                  </section>

                  {selectedService && selectedSlot && (
                    <div className="booking-summary">
                      <p className="booking-summary-label">Your appointment</p>
                      <strong>{selectedService.name}</strong>
                      <span>
                        {formatDayHeading(selectedDate)} at {formatTime(selectedSlot)}
                      </span>
                      <span>
                        {selectedService.durationMinutes} min ·{' '}
                        {formatPrice(selectedService.price, business.currency ?? 'GBP')}
                      </span>
                    </div>
                  )}

                  <button
                    className="btn btn-primary"
                    type="submit"
                    disabled={submitting || !selectedSlot}
                  >
                    {submitting ? 'Sending code…' : 'Email me a code'}
                  </button>
                </form>
              )
            )}
```

Also remove the now-unused `logout`/`isAuthenticated` destructuring **only if** nothing else in the file uses them (search first — `isAuthenticated` may still be used elsewhere; if unused, tsc will flag it in the build).

One prefill nuance: the existing effect that copies `user` details into `customer` only runs for `isCustomer` — leave it; guests start with empty fields, and the `customer` state is already initialised to empty strings.

- [ ] **Step 4: Run tests to verify they pass**

Run: `npm test`
Expected: PASS — the three new tests plus all pre-existing tests (the old "auth gate" test, if one exists, will now fail: update it to assert the new guest form instead, since the wall is intentionally gone).

- [ ] **Step 5: Typecheck + build**

Run: `npm run build`
Expected: clean tsc + Vite build.

- [ ] **Step 6: Commit**

```bash
git add src/pages/BookBusinessPage.tsx src/pages/BookBusinessPage.test.tsx
git commit -m "feat: guest booking with inline email code on booking page"
```

---

### Task 8: Styles + end-to-end verification

**Files:**
- Modify: `src/index.css` (frontend — only if the new elements need styles; `btn-link` may not exist yet)
- Modify: `src/main/resources/application.properties` (backend — document the new optional properties)

**Interfaces:**
- Consumes: everything above.
- Produces: shippable feature.

- [ ] **Step 1: Add `btn-link` style if missing**

Check `src/index.css` for `.btn-link`. If absent, add:

```css
.btn-link {
  background: none;
  border: none;
  padding: 0;
  color: var(--color-primary, #2563eb);
  text-decoration: underline;
  cursor: pointer;
  font-size: 0.9rem;
}
```

(If the stylesheet uses different custom-property names, match the link color used by `.back-link`.)

- [ ] **Step 2: Document backend properties**

Append to `src/main/resources/application.properties` alongside the existing `app.email-verification.*` entries:

```properties
# Guest booking OTP
app.booking-otp.expiry=10m
app.booking-otp.resend-interval=60s
app.password-reset.claim-expiry=24h
```

- [ ] **Step 3: Full verification, both repos**

Run (backend): `./mvnw -q test`
Expected: all green.
Run (frontend): `npm run build && npm test`
Expected: tsc clean, all green.

- [ ] **Step 4: Manual smoke test (if local stack available)**

Start backend + `npm run dev`, open a business booking page in a private window, complete steps 1–3, submit guest details, retrieve the code from the Resend dashboard/logs (or a configured test inbox), enter it, and confirm the booking appears in the owner dashboard.

- [ ] **Step 5: Commit**

```bash
# frontend repo
git add src/index.css && git commit -m "style: guest booking code form"
# backend repo
git add src/main/resources/application.properties && git commit -m "docs: guest booking OTP properties"
```

---

## Out of scope for this plan (later plans in the series)

- Auto-confirm toggle (Plan 2) — bookings created here still get status `PENDING`.
- Manage-booking links + My bookings page (Plan 3).
- Share kit (Plan 4).
- Code-review findings not touching these files (Plan 5), including the invalid `businesses.timezone` default, status-transition validation, and conflict-check race hardening.
- **Per-IP rate limiting** (spec deviation, agreed at plan review): this plan ships per-email limits (60s resend interval + 5-attempt cap, DB-backed). Per-IP throttling needs either an in-memory limiter component or an edge/proxy rule (Railway/Vercel); it lands with the hardening work in Plan 5 so the abuse surface is assessed once, holistically.
