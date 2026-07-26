# Manage Bookings Implementation Plan (Plan 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Customers can cancel and reschedule their own bookings — signed-in customers via a new "My bookings" page (JWT), guests via a hashed manage-link token carried in the now-always-on booking-details email.

**Architecture:** One `booking_manage_tokens` row per booking (hashed, same pattern as password-reset tokens; valid until the appointment ends, validity derived from the booking — no expiry column to keep in sync). A `BookingManageTokenService` issues/resolves tokens; a `ManageBookingService` owns view/cancel/reschedule for both entry paths and the cancellation-notice cutoff; thin controllers on top (`/api/v1/public/manage/{token}` + `/api/v1/customers/me/bookings`). Frontend adds `/manage/booking/:token` and `/my-bookings` pages sharing one `RescheduleSlotPicker` that reuses the public availability API and `BookingCalendar`.

**Tech Stack:** Spring Boot 4 / Java 21 / Flyway / MapStruct / Mockito (repo `booking-backend-app-java`); React 19 / TypeScript / React Router 7 / Vitest + Testing Library (repo `bookingsystem-frontend`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-22-booking-conversion-design.md` section 3. Locked decisions: signed-in customers use JWT (token link checked only when no session applies — in practice each frontend page uses its own entry path); token is random 32 bytes, stored hashed, tied to the booking, valid until the appointment ends; cancel/reschedule allowed until `cancellation_notice_hours` before start; inside the cutoff the page shows business phone/email; business is emailed on customer cancels/reschedules; booking-details email becomes **always-on** and carries the manage link.
- Token generation/hash MUST mirror `PasswordResetService`: `SecureRandom` 32 bytes → `Base64.getUrlEncoder().withoutPadding()` → stored as `EmailVerificationService.hash(rawToken)` (SHA-256 hex, 64 chars → `VARCHAR(64)`; Hibernate `ddl-auto=validate`, never CHAR).
- Exact copy strings (customer-facing, use verbatim):
  - Invalid/expired link (backend + frontend): "This manage link is invalid or has expired"
  - Cutoff violation (backend): "This booking can no longer be changed online. Please contact {business name} to make changes."
  - Cancellation reason recorded: "Cancelled by customer"
- Reschedule = `availabilityService.ensureSlotAvailable` (schedule-hours + conflicts) then existing `BookingService.reschedule` (keeps the booking's status — locked by spec). Known accepted limitation: a new time overlapping the booking's *own current slot* is reported unavailable (the availability UI never offers such slots either, so UI and server agree); revisit in Plan 5 if ever needed.
- Per-IP rate limiting on the public manage endpoints is deferred to Plan 5 (same decision as Plans 1–2; token entropy is the guard).
- Notification emails are best-effort: a failed email must never fail the cancel/reschedule/booking (same semantics as `sendBookingDetails`).
- All customer ownership checks compare emails case-insensitively (`Customer` has no FK to `User`; customers are per-business rows matched by normalized email — see `CustomerService.getOrCreateFromUser`).
- Two repos, branch `feature/manage-bookings` in each. `cd` explicitly per command; never rely on carried-over cwd.
- Backend test caveat: `JavabookingappApplicationTests.contextLoads` fails locally without a live DB — pre-existing/environmental, the ONLY acceptable failure.
- Backend package root: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/` (below, `.../` = this root).

## File Structure

**Backend:**
- Create: `javabookingapp/src/main/resources/db/migration/V11__booking_manage_tokens.sql`
- Create: `.../entity/BookingManageToken.java`, `.../repository/BookingManageTokenRepository.java`
- Create: `.../service/BookingManageTokenService.java` — issue/resolve/link-building only
- Create: `.../dto/response/ManageBookingResponse.java`, `.../dto/request/BookingRescheduleRequest.java`
- Create: `.../service/ManageBookingService.java` — view/cancel/reschedule for token + customer paths, cutoff logic
- Create: `.../controller/CustomerBookingsController.java`
- Modify: `.../controller/PublicController.java` — 3 manage endpoints
- Modify: `.../service/BookingNotificationService.java` — manage-link param, explicit PENDING copy, 2 business-notice methods
- Modify: `.../service/BookingService.java` — always-on details email with manage link
- Modify: `.../repository/BookingRepository.java` — customer-email query
- Tests: create `BookingManageTokenServiceTest`, `ManageBookingServiceTest`; extend `BookingNotificationServiceTest`, `BookingServiceTest`

**Frontend:**
- Modify: `src/types/api.ts` — `ManageBooking` interface
- Create: `src/api/manage.ts`, `src/api/customerBookings.ts`
- Create: `src/components/RescheduleSlotPicker.tsx` (+ test)
- Create: `src/pages/ManageBookingPage.tsx` (+ test), `src/pages/MyBookingsPage.tsx` (+ test)
- Modify: `src/App.tsx` — 2 routes + `CustomerRoute`; `src/components/PublicLayout.tsx` — "My bookings" nav link
- Modify: `src/pages/BookBusinessPage.tsx` — remove the "Email me my booking details" checkbox (email is always-on now)

---

### Task 1: Backend — V11 migration, token entity, repository

**Files:**
- Create: `javabookingapp/src/main/resources/db/migration/V11__booking_manage_tokens.sql`
- Create: `.../entity/BookingManageToken.java`
- Create: `.../repository/BookingManageTokenRepository.java`

**Interfaces:**
- Consumes: `Booking` entity (exists).
- Produces: `BookingManageToken` (`getBooking()`, `getTokenHash()`, `setTokenHash(String)`; `@Builder`), `BookingManageTokenRepository.findByTokenHash(String)` and `findByBookingId(UUID)` returning `Optional<BookingManageToken>` — Task 2 relies on these exact signatures.

- [ ] **Step 1: Create the feature branch**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git checkout -b feature/manage-bookings main
```

- [ ] **Step 2: Write migration V11**

Create `javabookingapp/src/main/resources/db/migration/V11__booking_manage_tokens.sql`:

```sql
-- Plan 3: self-service manage-booking links. One token per booking, stored
-- hashed (same pattern as password_reset_tokens). No expiry column: validity
-- is derived from the booking (usable until the appointment ends).
CREATE TABLE booking_manage_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- [ ] **Step 3: Create the entity**

Create `.../entity/BookingManageToken.java` (mirrors `PasswordResetToken`):

```java
package com.dev.bookingapp.javabookingapp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "booking_manage_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingManageToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 4: Create the repository**

Create `.../repository/BookingManageTokenRepository.java`:

```java
package com.dev.bookingapp.javabookingapp.repository;

import com.dev.bookingapp.javabookingapp.entity.BookingManageToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingManageTokenRepository extends JpaRepository<BookingManageToken, UUID> {

    Optional<BookingManageToken> findByTokenHash(String tokenHash);

    Optional<BookingManageToken> findByBookingId(UUID bookingId);
}
```

- [ ] **Step 5: Compile**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw -q compile
```

Expected: BUILD SUCCESS (silent with `-q`).

- [ ] **Step 6: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add javabookingapp/src/main/resources/db/migration/V11__booking_manage_tokens.sql javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/entity/BookingManageToken.java javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/repository/BookingManageTokenRepository.java && git commit -m "feat: booking manage token table, entity and repository"
```

---

### Task 2: Backend — BookingManageTokenService (issue / resolve / link)

**Files:**
- Create: `.../service/BookingManageTokenService.java`
- Test: create `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingManageTokenServiceTest.java`

**Interfaces:**
- Consumes: Task 1's repository; `EmailVerificationService.hash(String)` (existing static SHA-256 hex); `Booking.getEndDatetime()`.
- Produces: `String issueLink(Booking booking)` (returns the full manage URL; replaces any existing token for the booking), `Booking resolve(String rawToken)` (throws `BadRequestException("This manage link is invalid or has expired")` for null/blank/unknown tokens and for bookings whose `endDatetime` is not in the future). Tasks 3 and 5 rely on these exact signatures.

- [ ] **Step 1: Write the failing tests**

Create `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingManageTokenServiceTest.java`:

```java
package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.BookingManageToken;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.repository.BookingManageTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingManageTokenServiceTest {

    @Mock
    private BookingManageTokenRepository tokenRepository;

    @InjectMocks
    private BookingManageTokenService tokenService;

    private Booking booking;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tokenService, "frontendUrl", "https://bookingbase.co.uk/");
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .startDatetime(OffsetDateTime.now().plusDays(1))
                .endDatetime(OffsetDateTime.now().plusDays(1).plusMinutes(45))
                .build();
    }

    @Test
    void issueLinkStoresHashedTokenAndReturnsManageUrl() {
        when(tokenRepository.findByBookingId(booking.getId())).thenReturn(Optional.empty());
        when(tokenRepository.save(any(BookingManageToken.class))).thenAnswer(inv -> inv.getArgument(0));

        String link = tokenService.issueLink(booking);

        assertThat(link).startsWith("https://bookingbase.co.uk/manage/booking/");
        String rawToken = link.substring(link.lastIndexOf('/') + 1);
        assertThat(rawToken).hasSizeGreaterThanOrEqualTo(43); // 32 bytes base64url

        ArgumentCaptor<BookingManageToken> captor = ArgumentCaptor.forClass(BookingManageToken.class);
        verify(tokenRepository).save(captor.capture());
        BookingManageToken saved = captor.getValue();
        assertThat(saved.getBooking()).isSameAs(booking);
        assertThat(saved.getTokenHash()).isEqualTo(EmailVerificationService.hash(rawToken));
        assertThat(saved.getTokenHash()).isNotEqualTo(rawToken); // never store the raw token
    }

    @Test
    void issueLinkReplacesTheExistingTokenForTheBooking() {
        BookingManageToken existing = BookingManageToken.builder()
                .id(UUID.randomUUID())
                .booking(booking)
                .tokenHash("old-hash")
                .build();
        when(tokenRepository.findByBookingId(booking.getId())).thenReturn(Optional.of(existing));
        when(tokenRepository.save(any(BookingManageToken.class))).thenAnswer(inv -> inv.getArgument(0));

        tokenService.issueLink(booking);

        ArgumentCaptor<BookingManageToken> captor = ArgumentCaptor.forClass(BookingManageToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId()); // same row, new hash
        assertThat(captor.getValue().getTokenHash()).isNotEqualTo("old-hash");
    }

    @Test
    void resolveReturnsBookingForAValidToken() {
        String raw = "some-raw-token";
        when(tokenRepository.findByTokenHash(EmailVerificationService.hash(raw)))
                .thenReturn(Optional.of(BookingManageToken.builder().booking(booking).build()));

        assertThat(tokenService.resolve(raw)).isSameAs(booking);
    }

    @Test
    void resolveRejectsUnknownBlankAndNullTokens() {
        when(tokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.resolve("nope"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This manage link is invalid or has expired");
        assertThatThrownBy(() -> tokenService.resolve("  "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> tokenService.resolve(null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void resolveRejectsTokensWhoseAppointmentHasEnded() {
        booking.setEndDatetime(OffsetDateTime.now().minusMinutes(1));
        String raw = "expired-booking-token";
        when(tokenRepository.findByTokenHash(EmailVerificationService.hash(raw)))
                .thenReturn(Optional.of(BookingManageToken.builder().booking(booking).build()));

        assertThatThrownBy(() -> tokenService.resolve(raw))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This manage link is invalid or has expired");
    }
}
```

- [ ] **Step 2: Run to verify red**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=BookingManageTokenServiceTest
```

Expected: COMPILATION ERROR — `BookingManageTokenService` does not exist. That is the red step.

- [ ] **Step 3: Implement the service**

Create `.../service/BookingManageTokenService.java`:

```java
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

    @Value("${app.frontend-url:http://localhost:3000}")
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
```

- [ ] **Step 4: Run to verify green**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=BookingManageTokenServiceTest
```

Expected: PASS — 5 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/service/BookingManageTokenService.java javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingManageTokenServiceTest.java && git commit -m "feat: manage-token issue and resolve service"
```

---

### Task 3: Backend — ManageBookingService + business notification emails

**Files:**
- Create: `.../dto/response/ManageBookingResponse.java`
- Create: `.../service/ManageBookingService.java`
- Modify: `.../service/BookingNotificationService.java` — add `sendBusinessCancelledNotice` and `sendBusinessRescheduledNotice`
- Modify: `.../repository/BookingRepository.java` — add customer-email query
- Test: create `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/ManageBookingServiceTest.java`; extend `BookingNotificationServiceTest.java`

**Interfaces:**
- Consumes: Task 2's `tokenService.resolve(raw)`; existing `BookingService.reschedule(businessId, bookingId, newStart)`, `AvailabilityService.ensureSlotAvailable(business, service, start)`, `BookingMapper.toResponse`, `ResendEmailSender.send(fromName, to, replyTo, subject, text)` / `isConfigured()`, `AvailabilityService.resolveZone`.
- Produces (Task 4's controllers rely on these exact signatures):
  - `ManageBookingResponse getByToken(String rawToken)`
  - `ManageBookingResponse cancelByToken(String rawToken)`
  - `ManageBookingResponse rescheduleByToken(String rawToken, OffsetDateTime newStart)`
  - `List<ManageBookingResponse> listForCustomer(String email)`
  - `ManageBookingResponse cancelForCustomer(String email, UUID bookingId)`
  - `ManageBookingResponse rescheduleForCustomer(String email, UUID bookingId, OffsetDateTime newStart)`
  - `ManageBookingResponse` fields: `booking` (BookingResponse), `businessName`, `businessSlug`, `businessEmail`, `businessPhone`, `cancellationNoticeHours` (Integer), `bookingAdvanceDays` (Integer), `canCancel` (boolean), `canReschedule` (boolean) — the frontend types in Task 6 mirror these names exactly.

- [ ] **Step 1: Create the response DTO**

Create `.../dto/response/ManageBookingResponse.java`:

```java
package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ManageBookingResponse {
    private BookingResponse booking;
    private String businessName;
    private String businessSlug;
    private String businessEmail;
    private String businessPhone;
    private Integer cancellationNoticeHours;
    private Integer bookingAdvanceDays;
    private boolean canCancel;
    private boolean canReschedule;
}
```

- [ ] **Step 2: Add the repository query**

In `.../repository/BookingRepository.java`, add after `findByCustomerId`:

```java
    @Query("SELECT b FROM Booking b WHERE LOWER(b.customer.email) = LOWER(:email) " +
           "ORDER BY b.startDatetime DESC")
    List<Booking> findByCustomerEmail(@Param("email") String email);
```

- [ ] **Step 3: Write the failing ManageBookingService tests**

Create `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/ManageBookingServiceTest.java`:

```java
package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManageBookingServiceTest {

    @Mock
    private BookingManageTokenService tokenService;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingMapper bookingMapper;
    @Mock
    private AvailabilityService availabilityService;
    @Mock
    private BookingService bookingService;
    @Mock
    private BookingNotificationService notificationService;

    @InjectMocks
    private ManageBookingService manageBookingService;

    private Business business;
    private com.dev.bookingapp.javabookingapp.entity.Service service;
    private Customer customer;
    private Booking booking;

    @BeforeEach
    void setUp() {
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .slug("absolutelyfabuloushairandbeauty")
                .email("salon@example.com")
                .phone("01234 567890")
                .cancellationNoticeHours(24)
                .bookingAdvanceDays(30)
                .build();
        service = com.dev.bookingapp.javabookingapp.entity.Service.builder()
                .id(UUID.randomUUID())
                .business(business)
                .name("Haircut")
                .durationMinutes(45)
                .build();
        customer = Customer.builder()
                .id(UUID.randomUUID())
                .business(business)
                .email("jane@example.com")
                .firstName("Jane")
                .lastName("Doe")
                .build();
        booking = Booking.builder()
                .id(UUID.randomUUID())
                .business(business)
                .service(service)
                .customer(customer)
                .status(BookingStatus.CONFIRMED)
                .startDatetime(OffsetDateTime.now().plusDays(3))
                .endDatetime(OffsetDateTime.now().plusDays(3).plusMinutes(45))
                .build();
    }

    @Test
    void getByTokenReturnsBookingWithBusinessContactAndFlags() {
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.getByToken("tok");

        assertThat(response.getBusinessName()).isEqualTo(business.getName());
        assertThat(response.getBusinessSlug()).isEqualTo(business.getSlug());
        assertThat(response.getBusinessEmail()).isEqualTo(business.getEmail());
        assertThat(response.getBusinessPhone()).isEqualTo(business.getPhone());
        assertThat(response.getCancellationNoticeHours()).isEqualTo(24);
        assertThat(response.getBookingAdvanceDays()).isEqualTo(30);
        assertThat(response.isCanCancel()).isTrue();
        assertThat(response.isCanReschedule()).isTrue();
    }

    @Test
    void flagsAreFalseInsideTheCancellationCutoff() {
        booking.setStartDatetime(OffsetDateTime.now().plusHours(2)); // < 24h notice
        booking.setEndDatetime(OffsetDateTime.now().plusHours(2).plusMinutes(45));
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.getByToken("tok");

        assertThat(response.isCanCancel()).isFalse();
        assertThat(response.isCanReschedule()).isFalse();
    }

    @Test
    void flagsAreFalseForCancelledBookings() {
        booking.setStatus(BookingStatus.CANCELLED);
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.getByToken("tok");

        assertThat(response.isCanCancel()).isFalse();
        assertThat(response.isCanReschedule()).isFalse();
    }

    @Test
    void cancelByTokenCancelsAndNotifiesTheBusiness() {
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(BookingResponse.builder().build());

        ManageBookingResponse response = manageBookingService.cancelByToken("tok");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancelledAt()).isNotNull();
        assertThat(booking.getCancellationReason()).isEqualTo("Cancelled by customer");
        assertThat(response.isCanCancel()).isFalse();
        verify(notificationService).sendBusinessCancelledNotice(any(), any());
    }

    @Test
    void cancelInsideCutoffIsRejectedWithContactMessage() {
        booking.setStartDatetime(OffsetDateTime.now().plusHours(2));
        booking.setEndDatetime(OffsetDateTime.now().plusHours(2).plusMinutes(45));
        when(tokenService.resolve("tok")).thenReturn(booking);

        assertThatThrownBy(() -> manageBookingService.cancelByToken("tok"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("contact Absolutely Fabulous Hair and Beauty");
        verify(bookingRepository, never()).save(any());
        verify(notificationService, never()).sendBusinessCancelledNotice(any(), any());
    }

    @Test
    void cancellingAnAlreadyCancelledBookingIsRejected() {
        booking.setStatus(BookingStatus.CANCELLED);
        when(tokenService.resolve("tok")).thenReturn(booking);

        assertThatThrownBy(() -> manageBookingService.cancelByToken("tok"))
                .isInstanceOf(BadRequestException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void rescheduleByTokenValidatesSlotReschedulesAndNotifies() {
        OffsetDateTime newStart = OffsetDateTime.now().plusDays(5);
        OffsetDateTime oldStart = booking.getStartDatetime();
        when(tokenService.resolve("tok")).thenReturn(booking);
        when(bookingService.reschedule(business.getId(), booking.getId(), newStart))
                .thenReturn(BookingResponse.builder().build());
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        manageBookingService.rescheduleByToken("tok", newStart);

        verify(availabilityService).ensureSlotAvailable(business, service, newStart);
        verify(bookingService).reschedule(business.getId(), booking.getId(), newStart);
        verify(notificationService).sendBusinessRescheduledNotice(any(), any(), org.mockito.ArgumentMatchers.eq(oldStart));
    }

    @Test
    void rescheduleInsideCutoffIsRejectedBeforeTouchingAvailability() {
        booking.setStartDatetime(OffsetDateTime.now().plusHours(2));
        booking.setEndDatetime(OffsetDateTime.now().plusHours(2).plusMinutes(45));
        when(tokenService.resolve("tok")).thenReturn(booking);

        assertThatThrownBy(() -> manageBookingService.rescheduleByToken(
                "tok", OffsetDateTime.now().plusDays(5)))
                .isInstanceOf(BadRequestException.class);
        verify(availabilityService, never()).ensureSlotAvailable(any(), any(), any());
        verify(bookingService, never()).reschedule(any(), any(), any());
    }

    @Test
    void listForCustomerMapsAllBookingsForTheEmail() {
        when(bookingRepository.findByCustomerEmail("jane@example.com"))
                .thenReturn(List.of(booking));
        when(bookingMapper.toResponse(booking)).thenReturn(BookingResponse.builder().build());

        List<ManageBookingResponse> list = manageBookingService.listForCustomer("jane@example.com");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getBusinessName()).isEqualTo(business.getName());
    }

    @Test
    void customerCannotTouchAnotherCustomersBooking() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> manageBookingService.cancelForCustomer(
                "intruder@example.com", booking.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void cancelForCustomerMatchesEmailCaseInsensitively() {
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(BookingResponse.builder().build());

        manageBookingService.cancelForCustomer("JANE@example.com", booking.getId());

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }
}
```

- [ ] **Step 4: Run to verify red**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=ManageBookingServiceTest
```

Expected: COMPILATION ERROR — `ManageBookingService`, `ManageBookingResponse` methods and the two notification methods don't exist yet.

- [ ] **Step 5: Add the business-notice emails**

In `.../service/BookingNotificationService.java`, add after `sendBookingDetails` (uses the existing `WHEN_FORMAT`, `emailSender`, `resolveZone` pattern):

```java
    /** Tells the business a customer cancelled online. Best-effort. */
    public void sendBusinessCancelledNotice(Business business, BookingResponse booking) {
        if (!emailSender.isConfigured()) {
            log.warn("Cancel notice requested but RESEND_API_KEY is not configured; skipping");
            return;
        }
        ZoneId zone = AvailabilityService.resolveZone(business.getTimezone());
        String when = booking.getStartDatetime().atZoneSameInstant(zone).format(WHEN_FORMAT);
        String text = "Hi,\n\n"
                + booking.getCustomer().getFirstName() + " " + booking.getCustomer().getLastName()
                + " has cancelled their booking:\n\n"
                + "Service: " + booking.getService().getName() + "\n"
                + "When: " + when + "\n\n"
                + "The slot is now free for other customers.\n";
        try {
            emailSender.send("BookingBase", business.getEmail(), null,
                    "Booking cancelled: " + booking.getService().getName(), text);
            log.info("Cancel notice sent to business {} for booking {}", business.getId(), booking.getId());
        } catch (Exception ex) {
            log.error("Failed to send cancel notice for booking {}", booking.getId(), ex);
        }
    }

    /** Tells the business a customer rescheduled online. Best-effort. */
    public void sendBusinessRescheduledNotice(Business business, BookingResponse booking,
                                              OffsetDateTime oldStart) {
        if (!emailSender.isConfigured()) {
            log.warn("Reschedule notice requested but RESEND_API_KEY is not configured; skipping");
            return;
        }
        ZoneId zone = AvailabilityService.resolveZone(business.getTimezone());
        String from = oldStart.atZoneSameInstant(zone).format(WHEN_FORMAT);
        String to = booking.getStartDatetime().atZoneSameInstant(zone).format(WHEN_FORMAT);
        String text = "Hi,\n\n"
                + booking.getCustomer().getFirstName() + " " + booking.getCustomer().getLastName()
                + " has moved their booking:\n\n"
                + "Service: " + booking.getService().getName() + "\n"
                + "From: " + from + "\n"
                + "To: " + to + "\n";
        try {
            emailSender.send("BookingBase", business.getEmail(), null,
                    "Booking rescheduled: " + booking.getService().getName(), text);
            log.info("Reschedule notice sent to business {} for booking {}", business.getId(), booking.getId());
        } catch (Exception ex) {
            log.error("Failed to send reschedule notice for booking {}", booking.getId(), ex);
        }
    }
```

Add the import `java.time.OffsetDateTime` to the file's imports.

- [ ] **Step 6: Implement ManageBookingService**

Create `.../service/ManageBookingService.java`:

```java
package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BookingMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Customer self-service cancel/reschedule. Two entry paths share the same
 * rules: a manage-link token (guests) or the signed-in customer's email
 * (ownership is by case-insensitive email match — Customer rows have no FK
 * to User accounts). Changes are allowed until cancellation_notice_hours
 * before the start; inside the cutoff customers are told to contact the
 * business directly.
 */
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ManageBookingService {

    private final BookingManageTokenService tokenService;
    private final BookingRepository bookingRepository;
    private final BookingMapper bookingMapper;
    private final AvailabilityService availabilityService;
    private final BookingService bookingService;
    private final BookingNotificationService notificationService;

    // ---- token path -------------------------------------------------------

    @Transactional(readOnly = true)
    public ManageBookingResponse getByToken(String rawToken) {
        return toResponse(tokenService.resolve(rawToken));
    }

    @Transactional
    public ManageBookingResponse cancelByToken(String rawToken) {
        return cancel(tokenService.resolve(rawToken));
    }

    @Transactional
    public ManageBookingResponse rescheduleByToken(String rawToken, OffsetDateTime newStart) {
        return reschedule(tokenService.resolve(rawToken), newStart);
    }

    // ---- signed-in customer path ------------------------------------------

    @Transactional(readOnly = true)
    public List<ManageBookingResponse> listForCustomer(String email) {
        return bookingRepository.findByCustomerEmail(email).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ManageBookingResponse cancelForCustomer(String email, UUID bookingId) {
        return cancel(owned(email, bookingId));
    }

    @Transactional
    public ManageBookingResponse rescheduleForCustomer(String email, UUID bookingId,
                                                       OffsetDateTime newStart) {
        return reschedule(owned(email, bookingId), newStart);
    }

    /** Loads a booking only if it belongs to this customer's email; otherwise
     *  404 — never reveal whether someone else's booking id exists. */
    private Booking owned(String email, UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", "id", bookingId));
        String customerEmail = booking.getCustomer() != null
                ? booking.getCustomer().getEmail() : null;
        if (customerEmail == null || !customerEmail.equalsIgnoreCase(email)) {
            throw new ResourceNotFoundException("Booking", "id", bookingId);
        }
        return booking;
    }

    // ---- shared rules -----------------------------------------------------

    private ManageBookingResponse cancel(Booking booking) {
        OffsetDateTime now = OffsetDateTime.now();
        requireModifiable(booking, now);

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        booking.setCancellationReason("Cancelled by customer");
        Booking saved = bookingRepository.save(booking);

        notificationService.sendBusinessCancelledNotice(
                saved.getBusiness(), bookingMapper.toResponse(saved));
        return toResponse(saved);
    }

    private ManageBookingResponse reschedule(Booking booking, OffsetDateTime newStart) {
        OffsetDateTime now = OffsetDateTime.now();
        requireModifiable(booking, now);

        OffsetDateTime oldStart = booking.getStartDatetime();
        // Full availability check (opening hours, breaks, notice/advance
        // limits, clashes) before the conflict-checked reschedule itself.
        availabilityService.ensureSlotAvailable(
                booking.getBusiness(), booking.getService(), newStart);
        bookingService.reschedule(booking.getBusiness().getId(), booking.getId(), newStart);

        notificationService.sendBusinessRescheduledNotice(
                booking.getBusiness(), bookingMapper.toResponse(booking), oldStart);
        return toResponse(booking);
    }

    private void requireModifiable(Booking booking, OffsetDateTime now) {
        if (!canModifyAt(booking, now)) {
            throw new BadRequestException(
                    "This booking can no longer be changed online. Please contact "
                            + booking.getBusiness().getName() + " to make changes.");
        }
    }

    private boolean canModifyAt(Booking booking, OffsetDateTime now) {
        if (booking.getStatus() != BookingStatus.PENDING
                && booking.getStatus() != BookingStatus.CONFIRMED) {
            return false;
        }
        int noticeHours = booking.getBusiness().getCancellationNoticeHours() != null
                ? booking.getBusiness().getCancellationNoticeHours() : 0;
        return booking.getStartDatetime().isAfter(now.plusHours(noticeHours));
    }

    private ManageBookingResponse toResponse(Booking booking) {
        Business business = booking.getBusiness();
        boolean modifiable = canModifyAt(booking, OffsetDateTime.now());
        return ManageBookingResponse.builder()
                .booking(bookingMapper.toResponse(booking))
                .businessName(business.getName())
                .businessSlug(business.getSlug())
                .businessEmail(business.getEmail())
                .businessPhone(business.getPhone())
                .cancellationNoticeHours(business.getCancellationNoticeHours())
                .bookingAdvanceDays(business.getBookingAdvanceDays())
                .canCancel(modifiable)
                .canReschedule(modifiable)
                .build();
    }
}
```

- [ ] **Step 7: Run to verify green**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=ManageBookingServiceTest
```

Expected: PASS — 11 tests, 0 failures.

- [ ] **Step 8: Add notification tests (red then green is not required — the methods now exist; these pin the copy)**

In `BookingNotificationServiceTest.java`, add:

```java
    @Test
    void businessCancelNoticeNamesCustomerServiceAndFreedSlot() {
        notificationService.sendBusinessCancelledNotice(
                business, booking(BookingStatus.CANCELLED));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(
                eq("BookingBase"),
                eq(business.getEmail()),
                eq(null),
                eq("Booking cancelled: Haircut"),
                textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("Jane");
        assertThat(textCaptor.getValue()).contains("has cancelled their booking");
        assertThat(textCaptor.getValue()).contains("now free for other customers");
    }

    @Test
    void businessRescheduleNoticeShowsOldAndNewTimes() {
        notificationService.sendBusinessRescheduledNotice(
                business, booking(BookingStatus.CONFIRMED),
                OffsetDateTime.now().plusDays(2));

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(
                eq("BookingBase"),
                eq(business.getEmail()),
                eq(null),
                eq("Booking rescheduled: Haircut"),
                textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("has moved their booking");
        assertThat(textCaptor.getValue()).contains("From: ");
        assertThat(textCaptor.getValue()).contains("To: ");
    }
```

Note: the existing `booking(...)` helper needs a `lastName` on its `CustomerInfo` — add `.lastName("Doe")` to the helper's builder chain if it is not already set.

- [ ] **Step 9: Run both notification + manage suites**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest='BookingNotificationServiceTest,ManageBookingServiceTest'
```

Expected: PASS — 15 tests, 0 failures (4 notification + 11 manage).

- [ ] **Step 10: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add -A javabookingapp/src/main/java javabookingapp/src/test/java && git commit -m "feat: manage-booking service with cancel, reschedule, cutoff and business notices"
```

---

### Task 4: Backend — controllers (public manage + customers/me)

**Files:**
- Create: `.../dto/request/BookingRescheduleRequest.java`
- Create: `.../controller/CustomerBookingsController.java`
- Modify: `.../controller/PublicController.java`

**Interfaces:**
- Consumes: Task 3's `ManageBookingService` methods exactly as produced. `UserPrincipal.getEmail()` exists (Lombok getter; `principal.emailVerified` is already used in `@PreAuthorize` on `PublicController:84`).
- Produces HTTP API (Task 6's frontend clients mirror these paths):
  - `GET  /api/v1/public/manage/{token}` → 200 `ManageBookingResponse`
  - `POST /api/v1/public/manage/{token}/cancel` → 200 `ManageBookingResponse`
  - `POST /api/v1/public/manage/{token}/reschedule` (body `{"startDatetime": ...}`) → 200 `ManageBookingResponse`
  - `GET  /api/v1/customers/me/bookings` → 200 `List<ManageBookingResponse>`
  - `POST /api/v1/customers/me/bookings/{bookingId}/cancel` → 200 `ManageBookingResponse`
  - `POST /api/v1/customers/me/bookings/{bookingId}/reschedule` (same body) → 200 `ManageBookingResponse`
- SecurityConfig needs NO change: `/api/v1/public/**` is already `permitAll`, everything else already requires authentication; role enforcement is `@PreAuthorize` on the new controller.

Thin delegation — controller behavior is covered by Task 3's service tests; the gate here is compilation plus the full-suite run in Task 9 (matches this repo's existing controller convention: no controller-layer tests exist).

- [ ] **Step 1: Create the reschedule request DTO**

Create `.../dto/request/BookingRescheduleRequest.java`:

```java
package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class BookingRescheduleRequest {

    @NotNull(message = "New start time is required")
    private OffsetDateTime startDatetime;
}
```

- [ ] **Step 2: Add the public manage endpoints**

In `.../controller/PublicController.java`, add (inject `ManageBookingService` as a new constructor dependency alongside the existing ones; add the imports for `ManageBookingResponse` and `BookingRescheduleRequest`):

```java
    @GetMapping("/manage/{token}")
    public ResponseEntity<ManageBookingResponse> getManagedBooking(@PathVariable String token) {
        return ResponseEntity.ok(manageBookingService.getByToken(token));
    }

    @PostMapping("/manage/{token}/cancel")
    public ResponseEntity<ManageBookingResponse> cancelManagedBooking(@PathVariable String token) {
        return ResponseEntity.ok(manageBookingService.cancelByToken(token));
    }

    @PostMapping("/manage/{token}/reschedule")
    public ResponseEntity<ManageBookingResponse> rescheduleManagedBooking(
            @PathVariable String token,
            @Valid @RequestBody BookingRescheduleRequest request) {
        return ResponseEntity.ok(
                manageBookingService.rescheduleByToken(token, request.getStartDatetime()));
    }
```

- [ ] **Step 3: Create the customer controller**

Create `.../controller/CustomerBookingsController.java`:

```java
package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRescheduleRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ManageBookingResponse;
import com.dev.bookingapp.javabookingapp.security.UserPrincipal;
import com.dev.bookingapp.javabookingapp.service.ManageBookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Signed-in customers manage their own bookings by session — no token. */
@RestController
@RequestMapping("/api/v1/customers/me/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER') and principal.emailVerified == true")
public class CustomerBookingsController {

    private final ManageBookingService manageBookingService;

    @GetMapping
    public ResponseEntity<List<ManageBookingResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(manageBookingService.listForCustomer(principal.getEmail()));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ManageBookingResponse> cancel(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID bookingId) {
        return ResponseEntity.ok(
                manageBookingService.cancelForCustomer(principal.getEmail(), bookingId));
    }

    @PostMapping("/{bookingId}/reschedule")
    public ResponseEntity<ManageBookingResponse> reschedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID bookingId,
            @Valid @RequestBody BookingRescheduleRequest request) {
        return ResponseEntity.ok(manageBookingService.rescheduleForCustomer(
                principal.getEmail(), bookingId, request.getStartDatetime()));
    }
}
```

- [ ] **Step 4: Compile**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw -q compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/dto/request/BookingRescheduleRequest.java javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/controller/CustomerBookingsController.java javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/controller/PublicController.java && git commit -m "feat: public manage-token and customers/me booking endpoints"
```

---

### Task 5: Backend — always-on details email carrying the manage link

**Files:**
- Modify: `.../service/BookingNotificationService.java` — `sendBookingDetails` gains a `manageLink` parameter; pending copy becomes an explicit `PENDING` check
- Modify: `.../service/BookingService.java` — `createPublicBooking` always sends the email and issues the link first
- Test: extend `BookingNotificationServiceTest.java` and `BookingServiceTest.java`

**Interfaces:**
- Consumes: Task 2's `tokenService.issueLink(Booking)`; `bookingRepository.getReferenceById(UUID)` (JPA built-in) to hand the created booking entity to the token service.
- Produces: `sendBookingDetails(Business, BookingResponse, String customerEmail, String manageLink)` — 4-arg signature replaces the 3-arg one; `manageLink` nullable (paragraph omitted when null). `BookingService` gains constructor deps `BookingManageTokenService manageTokenService` (the `GuestBookingService` guest flow inherits always-on email automatically because it calls `createPublicBooking`).

- [ ] **Step 1: Write the failing tests**

In `BookingNotificationServiceTest.java`:

Update the existing `sentText(BookingStatus status)` helper to call the new 4-arg signature with a null link:

```java
        notificationService.sendBookingDetails(
                business, booking(status), "jane@example.com", null);
```

Add:

```java
    @Test
    void detailsEmailIncludesManageLinkWhenProvided() {
        notificationService.sendBookingDetails(
                business, booking(BookingStatus.CONFIRMED), "jane@example.com",
                "https://bookingbase.co.uk/manage/booking/abc123");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(any(), any(), any(), any(), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .contains("Need to make a change? Cancel or reschedule your booking here:")
                .contains("https://bookingbase.co.uk/manage/booking/abc123");
    }

    @Test
    void detailsEmailOmitsManageParagraphWhenLinkIsNull() {
        String text = sentText(BookingStatus.CONFIRMED);

        assertThat(text).doesNotContain("Cancel or reschedule");
    }
```

In `BookingServiceTest.java`: the class does NOT currently mock `BookingNotificationService` (the old email was gated behind `emailReminder`, which no test enabled, so the null field was never touched). Once the email is always-on, EVERY `createPublicBooking` test hits it — without these mocks they all NPE. Add BOTH fields to the mock list:

```java
    @Mock
    private BookingNotificationService bookingNotificationService;
    @Mock
    private BookingManageTokenService manageTokenService;
```

Then add:

```java
    @Test
    void publicBookingAlwaysSendsDetailsEmailWithManageLink() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getOrCreateFromUser(any(), any())).thenReturn(
                CustomerResponse.builder().id(customer.getId()).build());
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().id(UUID.randomUUID()).build());
        when(manageTokenService.issueLink(any())).thenReturn("https://x/manage/booking/tok");

        PublicBookingRequest request = publicRequest();
        request.setEmailReminder(false); // opting out must no longer suppress the email

        bookingService.createPublicBooking(business.getId(), request, customerAccount.getId());

        verify(bookingNotificationService).sendBookingDetails(
                eq(business), any(BookingResponse.class), eq(customerAccount.getEmail()),
                eq("https://x/manage/booking/tok"));
    }
```

(Static-import `eq` from `org.mockito.ArgumentMatchers` if not present.)

- [ ] **Step 2: Run to verify red**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest='BookingNotificationServiceTest,BookingServiceTest'
```

Expected: COMPILATION ERROR on the 4-arg `sendBookingDetails` — that is the red step.

- [ ] **Step 3: Implement**

In `BookingNotificationService.sendBookingDetails`, change the signature to:

```java
    public void sendBookingDetails(Business business, BookingResponse booking,
                                   String customerEmail, String manageLink) {
```

and rework the body's copy block (keep `intro`; the outro becomes an explicit status check and the manage paragraph is appended when a link exists):

```java
        boolean confirmed = booking.getStatus() == BookingStatus.CONFIRMED;
        boolean pending = booking.getStatus() == BookingStatus.PENDING;
        String intro = confirmed
                ? "Here are the details of your booking with " + business.getName() + ":\n\n"
                : "Here are the details of your booking request with " + business.getName() + ":\n\n";
        String outro;
        if (confirmed) {
            outro = "\nYou're all set — your booking is confirmed. " + business.getName()
                    + " will be in touch if anything changes.\n\n";
        } else if (pending) {
            outro = "\nYour booking is awaiting confirmation from " + business.getName()
                    + ". They will be in touch if anything changes.\n\n";
        } else {
            outro = "\nIf anything changes, " + business.getName() + " will be in touch.\n\n";
        }
        String managePart = manageLink != null
                ? "Need to make a change? Cancel or reschedule your booking here:\n"
                        + manageLink + "\n\n"
                : "";
        String text = "Hi " + booking.getCustomer().getFirstName() + ",\n\n"
                + intro
                + "Service: " + booking.getService().getName() + "\n"
                + "When: " + when + "\n"
                + "Duration: " + booking.getService().getDurationMinutes() + " minutes\n"
                + priceLine
                + outro
                + managePart
                + "See you soon!\n";
```

In `BookingService`:
- add `private final BookingManageTokenService manageTokenService;` to the constructor deps
- replace the emailReminder-gated send in `createPublicBooking` (currently `if (Boolean.TRUE.equals(request.getEmailReminder())) { bookingNotificationService.sendBookingDetails(business, created, account.getEmail()); }`) with:

```java
        // Always-on: the details email carries the customer's manage link.
        // Both are best-effort — the booking never fails because of them.
        String manageLink = null;
        try {
            manageLink = manageTokenService.issueLink(
                    bookingRepository.getReferenceById(created.getId()));
        } catch (RuntimeException ex) {
            log.error("Could not issue manage link for booking {}", created.getId(), ex);
        }
        bookingNotificationService.sendBookingDetails(
                business, created, account.getEmail(), manageLink);
```

Add `@Slf4j` to `BookingService` if it doesn't have a logger (it currently doesn't — add the `lombok.extern.slf4j.Slf4j` import and annotation).

- [ ] **Step 4: Run to verify green**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest='BookingNotificationServiceTest,BookingServiceTest'
```

Expected: PASS — 17 tests, 0 failures (6 notification + 11 booking). `GuestBookingServiceTest` mocks `BookingService`, so it is unaffected by the signature change — the full suite in Step 5 confirms.

- [ ] **Step 5: Full backend suite**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test
```

Expected: all pass except the known `contextLoads` environmental error.

- [ ] **Step 6: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add -A javabookingapp/src/main/java javabookingapp/src/test/java && git commit -m "feat: always-on booking details email with manage link"
```

---

### Task 6: Frontend — types, API clients, RescheduleSlotPicker, checkbox removal

**Files:**
- Modify: `src/types/api.ts` — add `ManageBooking`
- Create: `src/api/manage.ts`, `src/api/customerBookings.ts`
- Create: `src/components/RescheduleSlotPicker.tsx`
- Test: create `src/components/RescheduleSlotPicker.test.tsx`
- Modify: `src/pages/BookBusinessPage.tsx` — remove the "Email me my booking details" checkbox row (server sends it always now); keep the `emailReminder` state (it still feeds the stored flag and the saved draft shape — it just stays `true`)

**Interfaces:**
- Consumes: backend API from Tasks 3–4 (field names of `ManageBookingResponse` verbatim); existing `apiRequest` from `src/api/client.ts`; existing `publicApi.getAvailability(businessId, serviceId, date)`; existing `BookingCalendar` component (`businessId`, `serviceId`, `advanceDays`, `selectedDate`, `onSelect` props).
- Produces (Tasks 7–8 rely on these exactly):
  - `ManageBooking` type: `{ booking: Booking; businessName: string; businessSlug: string; businessEmail?: string; businessPhone?: string; cancellationNoticeHours?: number; bookingAdvanceDays?: number; canCancel: boolean; canReschedule: boolean }`
  - `manageApi`: `getManagedBooking(token)`, `cancelManagedBooking(token)`, `rescheduleManagedBooking(token, startDatetime)` → all `Promise<ManageBooking>`
  - `customerBookingsApi`: `getMyBookings(token)` → `Promise<ManageBooking[]>`; `cancelMyBooking(bookingId, token)`, `rescheduleMyBooking(bookingId, startDatetime, token)` → `Promise<ManageBooking>`
  - `<RescheduleSlotPicker businessId serviceId advanceDays onPick={(startDatetime: string) => ...} picking={boolean} onClose={() => ...} />`

- [ ] **Step 1: Create the feature branch**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git checkout -b feature/manage-bookings main
```

- [ ] **Step 2: Add the type**

In `src/types/api.ts`, after the `Booking` interface:

```ts
export interface ManageBooking {
  booking: Booking
  businessName: string
  businessSlug: string
  businessEmail?: string
  businessPhone?: string
  cancellationNoticeHours?: number
  bookingAdvanceDays?: number
  canCancel: boolean
  canReschedule: boolean
}
```

- [ ] **Step 3: Create the API clients**

Create `src/api/manage.ts`:

```ts
import { apiRequest } from './client'
import type { ManageBooking } from '../types/api'

export function getManagedBooking(token: string) {
  return apiRequest<ManageBooking>(`/public/manage/${token}`)
}

export function cancelManagedBooking(token: string) {
  return apiRequest<ManageBooking>(`/public/manage/${token}/cancel`, {
    method: 'POST',
  })
}

export function rescheduleManagedBooking(token: string, startDatetime: string) {
  return apiRequest<ManageBooking>(`/public/manage/${token}/reschedule`, {
    method: 'POST',
    body: { startDatetime },
  })
}
```

Create `src/api/customerBookings.ts`:

```ts
import { apiRequest } from './client'
import type { ManageBooking } from '../types/api'

export function getMyBookings(token: string) {
  return apiRequest<ManageBooking[]>('/customers/me/bookings', { token })
}

export function cancelMyBooking(bookingId: string, token: string) {
  return apiRequest<ManageBooking>(`/customers/me/bookings/${bookingId}/cancel`, {
    method: 'POST',
    token,
  })
}

export function rescheduleMyBooking(
  bookingId: string,
  startDatetime: string,
  token: string,
) {
  return apiRequest<ManageBooking>(
    `/customers/me/bookings/${bookingId}/reschedule`,
    { method: 'POST', body: { startDatetime }, token },
  )
}
```

- [ ] **Step 4: Write the failing picker test**

Create `src/components/RescheduleSlotPicker.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as publicApi from '../api/public'
import type { TimeSlot } from '../types/api'
import { RescheduleSlotPicker } from './RescheduleSlotPicker'

vi.mock('../api/public')

function tomorrowAt(hour: number): Date {
  const date = new Date()
  date.setDate(date.getDate() + 1)
  date.setHours(hour, 0, 0, 0)
  return date
}

function toDateInput(date: Date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

const slotNine = tomorrowAt(9)
const slots: TimeSlot[] = [
  {
    startDatetime: slotNine.toISOString(),
    endDatetime: new Date(slotNine.getTime() + 45 * 60000).toISOString(),
  },
]

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(publicApi.getAvailableDays).mockResolvedValue([toDateInput(slotNine)])
  vi.mocked(publicApi.getAvailability).mockResolvedValue(slots)
})

describe('RescheduleSlotPicker', () => {
  it('loads times for a picked day and reports the chosen slot', async () => {
    const onPick = vi.fn()
    const user = userEvent.setup()
    render(
      <RescheduleSlotPicker
        businessId="b-1"
        serviceId="s-1"
        advanceDays={30}
        onPick={onPick}
        picking={false}
        onClose={() => {}}
      />,
    )

    const label = slotNine.toLocaleDateString(undefined, {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    })
    let dayButton = screen.queryByRole('button', { name: label })
    if (!dayButton) {
      await user.click(screen.getByRole('button', { name: 'Next month' }))
      dayButton = await screen.findByRole('button', { name: label })
    }
    await user.click(dayButton!)

    const slotButton = await screen.findByRole('button', {
      name: slotNine.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
    })
    await user.click(slotButton)

    expect(publicApi.getAvailability).toHaveBeenCalledWith(
      'b-1',
      's-1',
      toDateInput(slotNine),
    )
    expect(onPick).toHaveBeenCalledWith(slots[0].startDatetime)
  })

  it('lets the customer back out without picking', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    render(
      <RescheduleSlotPicker
        businessId="b-1"
        serviceId="s-1"
        advanceDays={30}
        onPick={() => {}}
        picking={false}
        onClose={onClose}
      />,
    )

    await user.click(
      await screen.findByRole('button', { name: 'Keep current time' }),
    )
    expect(onClose).toHaveBeenCalled()
  })
})
```

- [ ] **Step 5: Run to verify red**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/components/RescheduleSlotPicker.test.tsx
```

Expected: FAIL — cannot resolve `./RescheduleSlotPicker`.

- [ ] **Step 6: Implement the picker**

Create `src/components/RescheduleSlotPicker.tsx`:

```tsx
import { useEffect, useState } from 'react'
import { ApiClientError } from '../api/client'
import * as publicApi from '../api/public'
import type { TimeSlot } from '../types/api'
import { BookingCalendar } from './BookingCalendar'

/** Day + time picker for moving an existing booking. Reuses the public
 *  availability API, so only genuinely free slots are offered. */
export function RescheduleSlotPicker({
  businessId,
  serviceId,
  advanceDays,
  onPick,
  picking,
  onClose,
}: {
  businessId: string
  serviceId: string
  advanceDays: number
  onPick: (startDatetime: string) => void | Promise<void>
  picking: boolean
  onClose: () => void
}) {
  const [selectedDate, setSelectedDate] = useState('')
  const [slots, setSlots] = useState<TimeSlot[]>([])
  const [loadingSlots, setLoadingSlots] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!selectedDate) return
    let cancelled = false
    setLoadingSlots(true)
    setError(null)
    publicApi
      .getAvailability(businessId, serviceId, selectedDate)
      .then((loaded) => {
        if (!cancelled) setSlots(loaded)
      })
      .catch((err) => {
        if (!cancelled) {
          setError(
            err instanceof ApiClientError
              ? err.message
              : 'Failed to load available times.',
          )
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingSlots(false)
      })
    return () => {
      cancelled = true
    }
  }, [businessId, serviceId, selectedDate])

  return (
    <div className="reschedule-picker">
      <h3>Pick a new time</h3>
      <BookingCalendar
        businessId={businessId}
        serviceId={serviceId}
        advanceDays={advanceDays}
        selectedDate={selectedDate}
        onSelect={setSelectedDate}
      />
      {error && <div className="error-banner">{error}</div>}
      {selectedDate &&
        (loadingSlots ? (
          <p className="slot-hint">Loading times…</p>
        ) : slots.length === 0 ? (
          <p className="slot-hint">No times available this day.</p>
        ) : (
          <div className="slot-grid">
            {slots.map((slot) => (
              <button
                key={slot.startDatetime}
                type="button"
                className="btn btn-secondary btn-sm"
                disabled={picking}
                onClick={() => onPick(slot.startDatetime)}
              >
                {new Date(slot.startDatetime).toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                })}
              </button>
            ))}
          </div>
        ))}
      <button
        type="button"
        className="btn btn-secondary btn-sm"
        onClick={onClose}
        disabled={picking}
      >
        Keep current time
      </button>
    </div>
  )
}
```

- [ ] **Step 7: Run to verify green**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/components/RescheduleSlotPicker.test.tsx
```

Expected: PASS — 2 tests.

- [ ] **Step 8: Remove the details-email checkbox**

In `src/pages/BookBusinessPage.tsx` (~line 699), delete ONLY the `<label className="checkbox-row">` block whose text is "Email me my booking details" (including its `field-hint` span). Do not touch the SMS checkbox below it, the `emailReminder` state, the draft shape, or the submit payloads — the flag simply stays `true`.

Run the page's tests (payloads already assert `emailReminder: true`, so they must still pass):

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/BookBusinessPage.test.tsx
```

Expected: PASS — 14 tests. If any test toggled that checkbox, update it to stop doing so (none are known to).

- [ ] **Step 9: Typecheck and commit**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx tsc -b && git add src/types/api.ts src/api/manage.ts src/api/customerBookings.ts src/components/RescheduleSlotPicker.tsx src/components/RescheduleSlotPicker.test.tsx src/pages/BookBusinessPage.tsx && git commit -m "feat: manage-booking API clients, reschedule slot picker, always-on email cleanup"
```

---

### Task 7: Frontend — ManageBookingPage (/manage/booking/:token)

**Files:**
- Create: `src/pages/ManageBookingPage.tsx`
- Test: create `src/pages/ManageBookingPage.test.tsx`
- Modify: `src/App.tsx` — route inside the existing `<Route element={<PublicLayout />}>` block

**Interfaces:**
- Consumes: Task 6's `manageApi` + `RescheduleSlotPicker` + `ManageBooking` type; `useParams` for `:token`; `ApiClientError` (`.status`) from `src/api/client.ts`.
- Produces: page states — loading; invalid link; details view with status eyebrow (`CANCELLED` → "Cancelled", `CONFIRMED` → "Confirmed ✓", `PENDING` → "Request sent"); cancel with inline confirm; reschedule via picker; inside-cutoff contact block. Route: `/manage/booking/:token`.

- [ ] **Step 1: Write the failing tests**

Create `src/pages/ManageBookingPage.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as manageApi from '../api/manage'
import * as publicApi from '../api/public'
import type { Booking, ManageBooking } from '../types/api'
import { ManageBookingPage } from './ManageBookingPage'

vi.mock('../api/manage')
vi.mock('../api/public')

const booking: Booking = {
  id: 'bk-1',
  businessId: 'b-1',
  status: 'CONFIRMED',
  startDatetime: new Date(Date.now() + 3 * 86400000).toISOString(),
  endDatetime: new Date(Date.now() + 3 * 86400000 + 45 * 60000).toISOString(),
  customer: {
    id: 'c-1',
    firstName: 'Gwen',
    lastName: 'Guest',
    email: 'gwen@example.com',
  },
  service: { id: 's-1', name: 'Haircut', durationMinutes: 45 },
} as Booking

const managed: ManageBooking = {
  booking,
  businessName: 'Absolutely Fabulous Hair and Beauty',
  businessSlug: 'absolutelyfabuloushairandbeauty',
  businessEmail: 'salon@example.com',
  businessPhone: '01234 567890',
  cancellationNoticeHours: 24,
  bookingAdvanceDays: 30,
  canCancel: true,
  canReschedule: true,
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/manage/booking/tok-123']}>
      <Routes>
        <Route path="/manage/booking/:token" element={<ManageBookingPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(manageApi.getManagedBooking).mockResolvedValue(managed)
  vi.mocked(publicApi.getAvailableDays).mockResolvedValue([])
  vi.mocked(publicApi.getAvailability).mockResolvedValue([])
})

describe('ManageBookingPage', () => {
  it('shows the booking with business name, service and status', async () => {
    renderPage()

    expect(
      await screen.findByText('Absolutely Fabulous Hair and Beauty'),
    ).toBeInTheDocument()
    expect(screen.getByText('Haircut')).toBeInTheDocument()
    expect(screen.getByText('Confirmed ✓')).toBeInTheDocument()
    expect(manageApi.getManagedBooking).toHaveBeenCalledWith('tok-123')
  })

  it('shows the invalid-link state for a dead token', async () => {
    // Any load failure renders the invalid state — the page never surfaces
    // backend copy here, so a plain Error is the simplest rejection.
    vi.mocked(manageApi.getManagedBooking).mockRejectedValue(
      new Error('dead link'),
    )
    renderPage()

    expect(
      await screen.findByText(/this manage link is invalid or has expired/i),
    ).toBeInTheDocument()
  })

  it('cancels after an explicit confirmation', async () => {
    vi.mocked(manageApi.cancelManagedBooking).mockResolvedValue({
      ...managed,
      booking: { ...booking, status: 'CANCELLED' },
      canCancel: false,
      canReschedule: false,
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Cancel booking' }))
    // confirm step, nothing sent yet
    expect(manageApi.cancelManagedBooking).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Yes, cancel it' }))

    await waitFor(() =>
      expect(manageApi.cancelManagedBooking).toHaveBeenCalledWith('tok-123'),
    )
    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
    expect(
      screen.getByText(/your booking has been cancelled/i),
    ).toBeInTheDocument()
  })

  it('shows the contact-the-business block inside the cutoff', async () => {
    vi.mocked(manageApi.getManagedBooking).mockResolvedValue({
      ...managed,
      canCancel: false,
      canReschedule: false,
    })
    renderPage()

    expect(
      await screen.findByText(/contact absolutely fabulous hair and beauty/i),
    ).toBeInTheDocument()
    expect(screen.getByText('01234 567890')).toBeInTheDocument()
    expect(screen.getByText('salon@example.com')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Cancel booking' }),
    ).not.toBeInTheDocument()
  })

  it('reschedules through the slot picker', async () => {
    const newStart = new Date(Date.now() + 5 * 86400000)
    newStart.setHours(9, 0, 0, 0)
    const month = String(newStart.getMonth() + 1).padStart(2, '0')
    const day = String(newStart.getDate()).padStart(2, '0')
    const isoDay = `${newStart.getFullYear()}-${month}-${day}`
    vi.mocked(publicApi.getAvailableDays).mockResolvedValue([isoDay])
    vi.mocked(publicApi.getAvailability).mockResolvedValue([
      {
        startDatetime: newStart.toISOString(),
        endDatetime: new Date(newStart.getTime() + 45 * 60000).toISOString(),
      },
    ])
    vi.mocked(manageApi.rescheduleManagedBooking).mockResolvedValue({
      ...managed,
      booking: { ...booking, startDatetime: newStart.toISOString() },
    })
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Reschedule' }))

    const label = newStart.toLocaleDateString(undefined, {
      weekday: 'long',
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    })
    let dayButton = screen.queryByRole('button', { name: label })
    if (!dayButton) {
      await user.click(screen.getByRole('button', { name: 'Next month' }))
      dayButton = await screen.findByRole('button', { name: label })
    }
    await user.click(dayButton!)
    await user.click(
      await screen.findByRole('button', {
        name: newStart.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      }),
    )

    await waitFor(() =>
      expect(manageApi.rescheduleManagedBooking).toHaveBeenCalledWith(
        'tok-123',
        newStart.toISOString(),
      ),
    )
    expect(
      await screen.findByText(/your booking has been moved/i),
    ).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify red**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/ManageBookingPage.test.tsx
```

Expected: FAIL — cannot resolve `./ManageBookingPage`.

- [ ] **Step 3: Implement the page**

Create `src/pages/ManageBookingPage.tsx`:

```tsx
import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import * as manageApi from '../api/manage'
import { RescheduleSlotPicker } from '../components/RescheduleSlotPicker'
import type { ManageBooking } from '../types/api'

function formatDateTime(value: string) {
  return new Date(value).toLocaleString(undefined, {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const STATUS_LABELS: Record<string, string> = {
  CONFIRMED: 'Confirmed ✓',
  PENDING: 'Request sent',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
  NO_SHOW: 'Missed',
}

export function ManageBookingPage() {
  const { token } = useParams()
  const [data, setData] = useState<ManageBooking | null>(null)
  const [loading, setLoading] = useState(true)
  const [invalid, setInvalid] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [rescheduling, setRescheduling] = useState(false)
  const [working, setWorking] = useState(false)

  const load = useCallback(async () => {
    if (!token) {
      setInvalid(true)
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      setData(await manageApi.getManagedBooking(token))
    } catch {
      // Any failure to load means the link cannot be used
      setInvalid(true)
    } finally {
      setLoading(false)
    }
  }, [token])

  useEffect(() => {
    load()
  }, [load])

  async function handleCancel() {
    if (!token) return
    setWorking(true)
    setError(null)
    try {
      setData(await manageApi.cancelManagedBooking(token))
      setNotice('Your booking has been cancelled.')
      setConfirmingCancel(false)
    } catch (err) {
      setError(
        err instanceof ApiClientError
          ? err.message
          : 'Could not cancel the booking. Please try again.',
      )
    } finally {
      setWorking(false)
    }
  }

  async function handlePick(startDatetime: string) {
    if (!token) return
    setWorking(true)
    setError(null)
    try {
      setData(await manageApi.rescheduleManagedBooking(token, startDatetime))
      setNotice('Your booking has been moved.')
      setRescheduling(false)
    } catch (err) {
      if (err instanceof ApiClientError && err.status === 409) {
        setError('That time was just taken — please pick another.')
      } else {
        setError(
          err instanceof ApiClientError
            ? err.message
            : 'Could not move the booking. Please try again.',
        )
      }
    } finally {
      setWorking(false)
    }
  }

  if (loading) {
    return (
      <div className="panel booking-panel" role="status">
        <p className="slot-hint">Loading your booking…</p>
      </div>
    )
  }

  if (invalid || !data) {
    return (
      <div className="panel booking-panel">
        <h2>This manage link is invalid or has expired</h2>
        <p>
          If your appointment is still coming up, use the link from your most
          recent booking email, or <Link to="/book">book again</Link>.
        </p>
      </div>
    )
  }

  const { booking } = data
  const cancelled = booking.status === 'CANCELLED'

  return (
    <div className="panel booking-panel">
      <p className="booking-eyebrow">
        {STATUS_LABELS[booking.status] ?? booking.status}
      </p>
      <h2>{data.businessName}</h2>
      <p>
        <strong>{booking.service.name}</strong> on{' '}
        <strong>{formatDateTime(booking.startDatetime)}</strong>
      </p>

      {notice && <div className="success-banner">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}

      {cancelled && !notice && (
        <p>This booking has been cancelled.</p>
      )}

      {rescheduling ? (
        <RescheduleSlotPicker
          businessId={booking.businessId}
          serviceId={booking.service.id}
          advanceDays={data.bookingAdvanceDays ?? 30}
          onPick={handlePick}
          picking={working}
          onClose={() => setRescheduling(false)}
        />
      ) : confirmingCancel ? (
        <div className="actions-row">
          <p>Cancel this booking?</p>
          <button
            className="btn btn-primary btn-sm"
            onClick={handleCancel}
            disabled={working}
          >
            {working ? 'Cancelling…' : 'Yes, cancel it'}
          </button>
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => setConfirmingCancel(false)}
            disabled={working}
          >
            Keep my booking
          </button>
        </div>
      ) : (
        <>
          {(data.canCancel || data.canReschedule) && (
            <div className="actions-row">
              {data.canReschedule && (
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => {
                    setNotice(null)
                    setRescheduling(true)
                  }}
                >
                  Reschedule
                </button>
              )}
              {data.canCancel && (
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => {
                    setNotice(null)
                    setConfirmingCancel(true)
                  }}
                >
                  Cancel booking
                </button>
              )}
            </div>
          )}
          {!cancelled && !data.canCancel && !data.canReschedule && (
            <div className="empty-state">
              <p>
                To change this booking, contact {data.businessName} directly
                {data.cancellationNoticeHours != null && (
                  <>
                    {' '}
                    (changes online close {data.cancellationNoticeHours} hours
                    before the appointment)
                  </>
                )}
                .
              </p>
              {data.businessPhone && <p>{data.businessPhone}</p>}
              {data.businessEmail && <p>{data.businessEmail}</p>}
            </div>
          )}
        </>
      )}

      <p>
        <Link to={`/book/${data.businessSlug}`}>
          Book another appointment with {data.businessName}
        </Link>
      </p>
    </div>
  )
}
```

- [ ] **Step 4: Add the route**

In `src/App.tsx`, add the import `import { ManageBookingPage } from './pages/ManageBookingPage'` and, inside the existing `<Route element={<PublicLayout />}>` block, after the `/book/:slug` route:

```tsx
        <Route path="/manage/booking/:token" element={<ManageBookingPage />} />
```

- [ ] **Step 5: Run to verify green**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/ManageBookingPage.test.tsx
```

Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git add src/pages/ManageBookingPage.tsx src/pages/ManageBookingPage.test.tsx src/App.tsx && git commit -m "feat: manage-booking page for email-link cancel and reschedule"
```

---

### Task 8: Frontend — MyBookingsPage, CustomerRoute, nav link

**Files:**
- Create: `src/pages/MyBookingsPage.tsx`
- Test: create `src/pages/MyBookingsPage.test.tsx`
- Modify: `src/App.tsx` — `CustomerRoute` guard + `/my-bookings` route
- Modify: `src/components/PublicLayout.tsx` — "My bookings" nav link in the signed-in customer section

**Interfaces:**
- Consumes: Task 6's `customerBookingsApi` + `RescheduleSlotPicker` + `ManageBooking`; `useAuth()` (`accessToken`, `isAuthenticated`, `isCustomer`, `isVerified`, `user`, `isLoading`) from `src/context/AuthContext`.
- Produces: `/my-bookings` route (customer-only; others redirected the same way `OwnerRoute` does it in reverse); page splits bookings into "Upcoming" (status not CANCELLED, start in future, ascending) and "Past & cancelled" (everything else, as returned); per-card Cancel (inline confirm) and Reschedule (inline picker).

- [ ] **Step 1: Write the failing tests**

Create `src/pages/MyBookingsPage.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as customerBookingsApi from '../api/customerBookings'
import * as publicApi from '../api/public'
import { AuthProvider } from '../context/AuthContext'
import type { Booking, ManageBooking } from '../types/api'
import { MyBookingsPage } from './MyBookingsPage'

vi.mock('../api/customerBookings')
vi.mock('../api/public')

function managed(overrides: {
  id: string
  status: Booking['status']
  daysFromNow: number
  canModify: boolean
}): ManageBooking {
  const start = new Date(Date.now() + overrides.daysFromNow * 86400000)
  return {
    booking: {
      id: overrides.id,
      businessId: 'b-1',
      status: overrides.status,
      startDatetime: start.toISOString(),
      endDatetime: new Date(start.getTime() + 45 * 60000).toISOString(),
      customer: {
        id: 'c-1',
        firstName: 'Jane',
        lastName: 'Doe',
        email: 'jane@example.com',
      },
      service: { id: 's-1', name: 'Haircut', durationMinutes: 45 },
    } as Booking,
    businessName: 'Absolutely Fabulous Hair and Beauty',
    businessSlug: 'absolutelyfabuloushairandbeauty',
    cancellationNoticeHours: 24,
    bookingAdvanceDays: 30,
    canCancel: overrides.canModify,
    canReschedule: overrides.canModify,
  }
}

function renderPage() {
  localStorage.setItem(
    'booking-auth',
    JSON.stringify({
      accessToken: 'access-token',
      refreshToken: 'refresh-token',
      business: null,
      user: {
        id: 'u-1',
        email: 'jane@example.com',
        firstName: 'Jane',
        lastName: 'Doe',
        fullName: 'Jane Doe',
        role: 'CUSTOMER',
        isActive: true,
        emailVerified: true,
      },
    }),
  )
  return render(
    <MemoryRouter initialEntries={['/my-bookings']}>
      <AuthProvider>
        <MyBookingsPage />
      </AuthProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  vi.mocked(customerBookingsApi.getMyBookings).mockResolvedValue([
    managed({ id: 'bk-up', status: 'CONFIRMED', daysFromNow: 3, canModify: true }),
    managed({ id: 'bk-old', status: 'COMPLETED', daysFromNow: -10, canModify: false }),
  ])
  vi.mocked(publicApi.getAvailableDays).mockResolvedValue([])
  vi.mocked(publicApi.getAvailability).mockResolvedValue([])
})

describe('MyBookingsPage', () => {
  it('splits bookings into upcoming and past', async () => {
    renderPage()

    expect(await screen.findByText('Upcoming')).toBeInTheDocument()
    expect(screen.getByText('Past & cancelled')).toBeInTheDocument()
    expect(
      screen.getAllByText('Absolutely Fabulous Hair and Beauty'),
    ).toHaveLength(2)
    expect(customerBookingsApi.getMyBookings).toHaveBeenCalledWith('access-token')
  })

  it('shows an empty state when there are no bookings', async () => {
    vi.mocked(customerBookingsApi.getMyBookings).mockResolvedValue([])
    renderPage()

    expect(
      await screen.findByText(/no bookings yet/i),
    ).toBeInTheDocument()
  })

  it('cancels a booking after confirmation and refreshes the list', async () => {
    vi.mocked(customerBookingsApi.cancelMyBooking).mockResolvedValue(
      managed({ id: 'bk-up', status: 'CANCELLED', daysFromNow: 3, canModify: false }),
    )
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Cancel booking' }))
    expect(customerBookingsApi.cancelMyBooking).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Yes, cancel it' }))

    await waitFor(() =>
      expect(customerBookingsApi.cancelMyBooking).toHaveBeenCalledWith(
        'bk-up',
        'access-token',
      ),
    )
    // list is reloaded after a change
    expect(customerBookingsApi.getMyBookings).toHaveBeenCalledTimes(2)
  })

  it('offers no actions on past bookings', async () => {
    vi.mocked(customerBookingsApi.getMyBookings).mockResolvedValue([
      managed({ id: 'bk-old', status: 'COMPLETED', daysFromNow: -10, canModify: false }),
    ])
    renderPage()

    await screen.findByText('Past & cancelled')
    expect(
      screen.queryByRole('button', { name: 'Cancel booking' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Reschedule' }),
    ).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run to verify red**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/MyBookingsPage.test.tsx
```

Expected: FAIL — cannot resolve `./MyBookingsPage`.

- [ ] **Step 3: Implement the page**

Create `src/pages/MyBookingsPage.tsx`:

```tsx
import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import * as customerBookingsApi from '../api/customerBookings'
import { RescheduleSlotPicker } from '../components/RescheduleSlotPicker'
import { useAuth } from '../context/AuthContext'
import type { ManageBooking } from '../types/api'

function formatDateTime(value: string) {
  return new Date(value).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const STATUS_LABELS: Record<string, string> = {
  CONFIRMED: 'Confirmed ✓',
  PENDING: 'Request sent',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
  NO_SHOW: 'Missed',
}

export function MyBookingsPage() {
  const { accessToken } = useAuth()
  const [items, setItems] = useState<ManageBooking[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [confirmingCancelId, setConfirmingCancelId] = useState<string | null>(null)
  const [reschedulingId, setReschedulingId] = useState<string | null>(null)
  const [working, setWorking] = useState(false)

  const load = useCallback(async () => {
    if (!accessToken) return
    setLoading(true)
    setError(null)
    try {
      setItems(await customerBookingsApi.getMyBookings(accessToken))
    } catch (err) {
      setError(
        err instanceof ApiClientError
          ? err.message
          : 'Failed to load your bookings.',
      )
    } finally {
      setLoading(false)
    }
  }, [accessToken])

  useEffect(() => {
    load()
  }, [load])

  async function handleCancel(bookingId: string) {
    if (!accessToken) return
    setWorking(true)
    setError(null)
    try {
      await customerBookingsApi.cancelMyBooking(bookingId, accessToken)
      setConfirmingCancelId(null)
      await load()
    } catch (err) {
      setError(
        err instanceof ApiClientError
          ? err.message
          : 'Could not cancel the booking. Please try again.',
      )
    } finally {
      setWorking(false)
    }
  }

  async function handlePick(bookingId: string, startDatetime: string) {
    if (!accessToken) return
    setWorking(true)
    setError(null)
    try {
      await customerBookingsApi.rescheduleMyBooking(
        bookingId,
        startDatetime,
        accessToken,
      )
      setReschedulingId(null)
      await load()
    } catch (err) {
      if (err instanceof ApiClientError && err.status === 409) {
        setError('That time was just taken — please pick another.')
      } else {
        setError(
          err instanceof ApiClientError
            ? err.message
            : 'Could not move the booking. Please try again.',
        )
      }
    } finally {
      setWorking(false)
    }
  }

  const now = Date.now()
  const upcoming = items
    .filter(
      (item) =>
        item.booking.status !== 'CANCELLED' &&
        new Date(item.booking.startDatetime).getTime() > now,
    )
    .sort(
      (a, b) =>
        new Date(a.booking.startDatetime).getTime() -
        new Date(b.booking.startDatetime).getTime(),
    )
  const past = items.filter((item) => !upcoming.includes(item))

  function renderCard(item: ManageBooking) {
    const { booking } = item
    return (
      <div className="panel" key={booking.id}>
        <p className="booking-eyebrow">
          {STATUS_LABELS[booking.status] ?? booking.status}
        </p>
        <h3>
          <Link to={`/book/${item.businessSlug}`}>{item.businessName}</Link>
        </h3>
        <p>
          <strong>{booking.service.name}</strong> on{' '}
          <strong>{formatDateTime(booking.startDatetime)}</strong>
        </p>
        {reschedulingId === booking.id ? (
          <RescheduleSlotPicker
            businessId={booking.businessId}
            serviceId={booking.service.id}
            advanceDays={item.bookingAdvanceDays ?? 30}
            onPick={(start) => handlePick(booking.id, start)}
            picking={working}
            onClose={() => setReschedulingId(null)}
          />
        ) : confirmingCancelId === booking.id ? (
          <div className="actions-row">
            <p>Cancel this booking?</p>
            <button
              className="btn btn-primary btn-sm"
              onClick={() => handleCancel(booking.id)}
              disabled={working}
            >
              {working ? 'Cancelling…' : 'Yes, cancel it'}
            </button>
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => setConfirmingCancelId(null)}
              disabled={working}
            >
              Keep my booking
            </button>
          </div>
        ) : (
          (item.canCancel || item.canReschedule) && (
            <div className="actions-row">
              {item.canReschedule && (
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => setReschedulingId(booking.id)}
                >
                  Reschedule
                </button>
              )}
              {item.canCancel && (
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={() => setConfirmingCancelId(booking.id)}
                >
                  Cancel booking
                </button>
              )}
            </div>
          )
        )}
      </div>
    )
  }

  if (loading) {
    return (
      <div className="panel" role="status">
        <p className="slot-hint">Loading your bookings…</p>
      </div>
    )
  }

  return (
    <div className="my-bookings">
      <h2>My bookings</h2>
      {error && <div className="error-banner">{error}</div>}
      {items.length === 0 ? (
        <div className="empty-state">
          No bookings yet. <Link to="/book">Book an appointment</Link> to get
          started.
        </div>
      ) : (
        <>
          <h3>Upcoming</h3>
          {upcoming.length === 0 ? (
            <p className="slot-hint">Nothing coming up.</p>
          ) : (
            upcoming.map(renderCard)
          )}
          <h3>Past &amp; cancelled</h3>
          {past.length === 0 ? (
            <p className="slot-hint">Nothing here yet.</p>
          ) : (
            past.map(renderCard)
          )}
        </>
      )}
    </div>
  )
}
```

- [ ] **Step 4: Add CustomerRoute + route + nav link**

In `src/App.tsx`:

Add the import `import { MyBookingsPage } from './pages/MyBookingsPage'`.

Add `CustomerRoute` next to `OwnerRoute` (same shape, inverse role check):

```tsx
function CustomerRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isCustomer, isVerified, user, isLoading } = useAuth()

  if (isLoading) {
    return <div className="auth-page">Loading…</div>
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  if (!isVerified) {
    return (
      <Navigate
        to={`/check-email?email=${encodeURIComponent(user?.email ?? '')}`}
        replace
      />
    )
  }

  // Business and admin accounts have no customer bookings
  if (!isCustomer) {
    return <Navigate to="/" replace />
  }

  return children
}
```

Inside the `<Route element={<PublicLayout />}>` block, after the manage route:

```tsx
        <Route
          path="/my-bookings"
          element={
            <CustomerRoute>
              <MyBookingsPage />
            </CustomerRoute>
          }
        />
```

In `src/components/PublicLayout.tsx`, inside the `{isAuthenticated && isCustomer && (` block, add as the FIRST child (before the `isVerified` ternary):

```tsx
              <Link to="/my-bookings" className="header-link">
                My bookings
              </Link>
```

- [ ] **Step 5: Run to verify green, then the full suite**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/MyBookingsPage.test.tsx && npm test && npx tsc -b
```

Expected: 4 new tests pass; full suite passes (47 pre-plan + 2 picker + 5 manage + 4 my-bookings = 58); tsc clean.

- [ ] **Step 6: Commit**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git add src/pages/MyBookingsPage.tsx src/pages/MyBookingsPage.test.tsx src/App.tsx src/components/PublicLayout.tsx && git commit -m "feat: my-bookings page with customer cancel and reschedule"
```

---

### Task 9: Whole-feature verification (both repos)

**Files:** none (fixes only if something fails — fix, re-run, commit in the owning repo).

**Interfaces:** consumes everything from Tasks 1–8.

- [ ] **Step 1: Full backend suite**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test
```

Expected: all unit tests pass; ONLY acceptable failure is the known `contextLoads` environmental error.

- [ ] **Step 2: Full frontend suite, typecheck, production build**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npm test && npx tsc -b && npm run build
```

Expected: 58 tests pass, tsc clean, Vite build succeeds.

- [ ] **Step 3: Confirm branch state**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git log --oneline main..feature/manage-bookings && git status --short && cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git log --oneline main..feature/manage-bookings && git status --short
```

Expected: backend 5 commits (Tasks 1–5), frontend 3 commits (Tasks 6–8), clean trees.
