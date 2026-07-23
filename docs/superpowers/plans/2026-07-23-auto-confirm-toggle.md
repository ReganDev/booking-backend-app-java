# Auto-Confirm Toggle Implementation Plan (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-business "auto-confirm bookings" toggle (default ON) so customers see "Confirmed ✓" instantly instead of "Request sent", with an owner dashboard setting to opt back into the request/approve flow.

**Architecture:** One new boolean column on `businesses` flows entity → `BookingService.create()` status decision → business DTOs → a new dashboard Settings panel; the customer confirmation screen and the booking-details email both branch on the booking's `status`. No new endpoints, no enum changes. Email *delivery* is unchanged (still opt-in; always-on ships with Plan 3's manage links).

**Tech Stack:** Spring Boot 4 / Java 21 / Flyway / MapStruct / Mockito (backend repo `booking-backend-app-java`); React 19 / TypeScript / Vite / Vitest + Testing Library (frontend repo `bookingsystem-frontend`).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-22-booking-conversion-design.md` section 2. Column is exactly `auto_confirm_bookings BOOLEAN NOT NULL DEFAULT TRUE` — existing businesses get auto-confirm ON via the default (explicit user decision).
- `BookingStatus` enum already has `PENDING` and `CONFIRMED` — do NOT touch the enum or the DB `booking_status` type.
- Null-safety idiom in this codebase is `Boolean.TRUE.equals(...)` — a null flag must fall back to `PENDING` (old behavior), never NPE.
- Hibernate runs `ddl-auto=validate`: entity column types/names must match the migration exactly (see V7 precedent — never CHAR).
- Customer-facing copy (spec): confirmed → "Confirmed ✓ — you're booked"; pending → current "Request sent — the business will confirm" copy. Owner toggle label: "Automatically confirm new bookings".
- **Deferred (recorded decision):** the spec's "booking-details email becomes always-on" ships with Plan 3 (manage links), not here — it only matters once the email carries the manage link. The email's *copy* is NOT deferred: it currently says "awaiting confirmation" unconditionally, which would be wrong for every auto-confirmed booking, so it branches on status in this plan (spec: "screen and email adapt").
- Two repos. Backend work on branch `feature/auto-confirm-toggle` in `booking-backend-app-java`; frontend work on branch `feature/auto-confirm-toggle` in `bookingsystem-frontend`. Every command below states its repo — `cd` explicitly per command; compound commands must not assume a carried-over cwd.
- Backend test caveat: `JavabookingappApplicationTests.contextLoads` fails locally without a live DB — pre-existing/environmental. It is the ONLY acceptable failure.
- Backend package root: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/`.

## File Structure

**Backend (`booking-backend-app-java`):**
- Create: `javabookingapp/src/main/resources/db/migration/V10__auto_confirm_bookings.sql` — the column
- Modify: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/entity/Business.java` — `autoConfirmBookings` field
- Modify: `.../dto/request/BusinessRequest.java`, `.../dto/response/BusinessResponse.java` — expose the flag (MapStruct maps it by name automatically; `BusinessMapper` needs no edits — its `updateEntity` already uses `NullValuePropertyMappingStrategy.IGNORE`, so requests omitting the flag don't clobber it)
- Modify: `.../service/BookingService.java` — status branch in `create()`
- Modify: `.../service/BookingNotificationService.java` — email copy branch on booking status
- Modify: `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingServiceTest.java`
- Create: `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingNotificationServiceTest.java`

**Frontend (`bookingsystem-frontend`):**
- Modify: `src/types/api.ts` — `autoConfirmBookings?: boolean` on `Business`
- Create: `src/components/BookingSettingsPanel.tsx` + `src/components/BookingSettingsPanel.test.tsx` — new dashboard panel (pattern: `OpeningHoursPanel`)
- Modify: `src/pages/DashboardPage.tsx` — new `settings` tab
- Modify: `src/pages/BookBusinessPage.tsx` — confirmation screen copy branch
- Modify: `src/pages/BookBusinessPage.test.tsx`

---

### Task 1: Backend — migration, entity field, DTO exposure

**Files:**
- Create: `javabookingapp/src/main/resources/db/migration/V10__auto_confirm_bookings.sql`
- Modify: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/entity/Business.java` (after `cancellationNoticeHours`, ~line 78)
- Modify: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/dto/request/BusinessRequest.java`
- Modify: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/dto/response/BusinessResponse.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Business.getAutoConfirmBookings()` / `setAutoConfirmBookings(Boolean)` (Lombok `@Getter`/`@Setter`; builder default `true`) — Task 2 relies on these exact accessors. `BusinessRequest.autoConfirmBookings` / `BusinessResponse.autoConfirmBookings` (`Boolean`) — Task 3's frontend relies on this JSON property name.

This is plumbing with no behavior change yet — Task 2's tests exercise the entity default. Verification here is compile-level.

- [ ] **Step 1: Create the feature branch**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git checkout -b feature/auto-confirm-toggle main
```

- [ ] **Step 2: Write migration V10**

Create `javabookingapp/src/main/resources/db/migration/V10__auto_confirm_bookings.sql`:

```sql
-- Plan 2: per-business auto-confirm toggle. Existing businesses get
-- auto-confirm ON via the default (explicit product decision).
ALTER TABLE businesses
    ADD COLUMN auto_confirm_bookings BOOLEAN NOT NULL DEFAULT TRUE;
```

- [ ] **Step 3: Add the entity field**

In `entity/Business.java`, inside the `// Booking settings` block, directly after the `cancellationNoticeHours` field:

```java
    @Builder.Default
    @Column(name = "auto_confirm_bookings", nullable = false)
    private Boolean autoConfirmBookings = true;
```

- [ ] **Step 4: Expose the flag in the DTOs**

In `dto/request/BusinessRequest.java`, after `private Integer cancellationNoticeHours;`:

```java
    private Boolean autoConfirmBookings;
```

In `dto/response/BusinessResponse.java`, after `private Integer cancellationNoticeHours;`:

```java
    private Boolean autoConfirmBookings;
```

No `BusinessMapper` change: MapStruct maps same-named properties automatically, and `updateEntity` ignores nulls, so a `PUT /businesses/{id}` that omits the flag leaves it untouched.

- [ ] **Step 5: Compile**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw -q compile
```

Expected: BUILD SUCCESS (no output with `-q` on success is fine).

- [ ] **Step 6: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add javabookingapp/src/main/resources/db/migration/V10__auto_confirm_bookings.sql javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/entity/Business.java javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/dto/request/BusinessRequest.java javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/dto/response/BusinessResponse.java && git commit -m "feat: add auto_confirm_bookings column, entity field and DTO exposure"
```

---

### Task 2: Backend — booking status decided by the toggle

**Files:**
- Modify: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/service/BookingService.java` (line 174: `booking.setStatus(BookingStatus.PENDING);`)
- Test: `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingServiceTest.java`

**Interfaces:**
- Consumes: `Business.getAutoConfirmBookings()` from Task 1.
- Produces: `BookingService.create()` now saves status `CONFIRMED` when the flag is `TRUE`, `PENDING` when `FALSE` **or null**. This applies to all callers — public bookings, guest OTP bookings (`GuestBookingService.verify` → `createPublicBooking` → `create`), and owner dashboard "New booking" (intended: an owner adding a phone booking shouldn't have to approve their own entry).

Note: the existing test `publicBookingHappyPathCreatesPendingBookingWithServicePrice` builds `Business` via the builder, which after Task 1 defaults the flag to `true` — so that test's `PENDING` assertion MUST flip to `CONFIRMED`. That is the desired product behavior, not a regression.

- [ ] **Step 1: Update the existing happy-path test and add two new tests (failing first)**

In `BookingServiceTest.java`:

Rename `publicBookingHappyPathCreatesPendingBookingWithServicePrice` to `publicBookingHappyPathConfirmsBookingWithServicePrice` and change its status assertion (line ~211):

```java
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
```

Then add these two tests at the end of the class (before the closing brace):

```java
    private BookingRequest directRequest() {
        BookingRequest request = new BookingRequest();
        request.setCustomerId(customer.getId());
        request.setServiceId(service.getId());
        request.setStartDatetime(start);
        return request;
    }

    private Booking createAndCaptureSavedBooking() {
        when(businessService.getEntityById(business.getId())).thenReturn(business);
        when(serviceService.getEntityById(service.getId())).thenReturn(service);
        when(customerService.getEntityById(customer.getId())).thenReturn(customer);
        when(bookingMapper.toEntity(any(BookingRequest.class))).thenReturn(new Booking());
        when(bookingRepository.findConflictingBusinessBookings(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class)))
                .thenReturn(BookingResponse.builder().build());

        bookingService.create(business.getId(), directRequest());

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void bookingStaysPendingWhenAutoConfirmIsOff() {
        business.setAutoConfirmBookings(false);

        Booking saved = createAndCaptureSavedBooking();

        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void bookingStaysPendingWhenAutoConfirmFlagIsNull() {
        // Defensive: a null flag (e.g. an entity built without the default)
        // must fall back to the old request/approve behavior, never confirm.
        business.setAutoConfirmBookings(null);

        Booking saved = createAndCaptureSavedBooking();

        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
    }
```

- [ ] **Step 2: Run the test class to verify the new tests fail**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=BookingServiceTest
```

Expected: FAIL — with status still hardcoded to `PENDING`, the renamed test `publicBookingHappyPathConfirmsBookingWithServicePrice` fails (expects `CONFIRMED`, gets `PENDING`). That failure is the red step. The two new `PENDING` tests pass already — they are regression pins for the branch you are about to add.

- [ ] **Step 3: Implement the status branch**

In `service/BookingService.java`, replace line 174:

```java
        booking.setStatus(BookingStatus.PENDING);
```

with:

```java
        // Auto-confirm (default ON) books instantly; opted-out businesses
        // keep the request/approve flow. Null falls back to PENDING.
        booking.setStatus(Boolean.TRUE.equals(business.getAutoConfirmBookings())
                ? BookingStatus.CONFIRMED
                : BookingStatus.PENDING);
```

- [ ] **Step 4: Run the test class to verify all pass**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=BookingServiceTest
```

Expected: PASS — 10 tests, 0 failures (8 existing + 2 new).

- [ ] **Step 5: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/service/BookingService.java javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingServiceTest.java && git commit -m "feat: auto-confirm toggle decides booking status in BookingService.create"
```

---

### Task 3: Backend — booking-details email copy branches on status

**Files:**
- Modify: `javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/service/BookingNotificationService.java` (lines 39–47, the `text` block)
- Test: Create `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingNotificationServiceTest.java`

**Interfaces:**
- Consumes: `BookingResponse.getStatus()` (already exists, `dto/response/BookingResponse.java:16`); `ResendEmailSender.send(fromName, to, replyTo, subject, text)` and `isConfigured()` (concrete class — Mockito mocks it fine).
- Produces: no signature changes. `sendBookingDetails` keeps its exact signature; only the email body text branches. Callers (`BookingService.createPublicBooking`, `GuestBookingService.verify`) are untouched.

The email currently says "your booking request … awaiting confirmation" unconditionally (`BookingNotificationService.java:40,45`) — wrong for every auto-confirmed booking, which is now the default path.

- [ ] **Step 1: Write the failing tests**

Create `javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingNotificationServiceTest.java`:

```java
package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingNotificationServiceTest {

    @Mock
    private ResendEmailSender emailSender;

    @InjectMocks
    private BookingNotificationService notificationService;

    private Business business;

    @BeforeEach
    void setUp() {
        business = Business.builder()
                .id(UUID.randomUUID())
                .name("Absolutely Fabulous Hair and Beauty")
                .email("salon@example.com")
                .timezone("Europe/London")
                .currency("GBP")
                .build();
        when(emailSender.isConfigured()).thenReturn(true);
    }

    private BookingResponse booking(BookingStatus status) {
        return BookingResponse.builder()
                .id(UUID.randomUUID())
                .status(status)
                .startDatetime(OffsetDateTime.now().plusDays(1))
                .price(new BigDecimal("32.50"))
                .customer(BookingResponse.CustomerInfo.builder()
                        .firstName("Jane")
                        .email("jane@example.com")
                        .build())
                .service(BookingResponse.ServiceInfo.builder()
                        .name("Haircut")
                        .durationMinutes(45)
                        .build())
                .build();
    }

    private String sentText(BookingStatus status) {
        notificationService.sendBookingDetails(
                business, booking(status), "jane@example.com");

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(
                eq(business.getName()),
                eq("jane@example.com"),
                eq(business.getEmail()),
                any(String.class),
                textCaptor.capture());
        return textCaptor.getValue();
    }

    @Test
    void confirmedBookingEmailSaysBookingIsConfirmed() {
        String text = sentText(BookingStatus.CONFIRMED);

        assertThat(text).contains("your booking is confirmed");
        assertThat(text).doesNotContain("awaiting confirmation");
        assertThat(text).doesNotContain("booking request");
    }

    @Test
    void pendingBookingEmailKeepsAwaitingConfirmationCopy() {
        String text = sentText(BookingStatus.PENDING);

        assertThat(text).contains("booking request");
        assertThat(text).contains("awaiting confirmation");
        assertThat(text).doesNotContain("your booking is confirmed");
    }
}
```

- [ ] **Step 2: Run the test class to verify the CONFIRMED test fails**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=BookingNotificationServiceTest
```

Expected: FAIL — `confirmedBookingEmailSaysBookingIsConfirmed` fails (text still contains "awaiting confirmation"); `pendingBookingEmailKeepsAwaitingConfirmationCopy` passes (it pins existing copy).

- [ ] **Step 3: Branch the email body**

In `BookingNotificationService.java`, replace the `text` block (lines 39–47):

```java
        String text = "Hi " + booking.getCustomer().getFirstName() + ",\n\n"
                + "Here are the details of your booking request with " + business.getName() + ":\n\n"
                + "Service: " + booking.getService().getName() + "\n"
                + "When: " + when + "\n"
                + "Duration: " + booking.getService().getDurationMinutes() + " minutes\n"
                + priceLine
                + "\nYour booking is awaiting confirmation from " + business.getName()
                + ". They will be in touch if anything changes.\n\n"
                + "See you soon!\n";
```

with:

```java
        boolean confirmed = booking.getStatus() == com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus.CONFIRMED;
        String intro = confirmed
                ? "Here are the details of your booking with " + business.getName() + ":\n\n"
                : "Here are the details of your booking request with " + business.getName() + ":\n\n";
        String outro = confirmed
                ? "\nYou're all set — your booking is confirmed. " + business.getName()
                        + " will be in touch if anything changes.\n\n"
                : "\nYour booking is awaiting confirmation from " + business.getName()
                        + ". They will be in touch if anything changes.\n\n";
        String text = "Hi " + booking.getCustomer().getFirstName() + ",\n\n"
                + intro
                + "Service: " + booking.getService().getName() + "\n"
                + "When: " + when + "\n"
                + "Duration: " + booking.getService().getDurationMinutes() + " minutes\n"
                + priceLine
                + outro
                + "See you soon!\n";
```

Also add the import at the top of the file (with the other imports) and drop the fully-qualified name in the `confirmed` line:

```java
import com.dev.bookingapp.javabookingapp.entity.enums.BookingStatus;
```

```java
        boolean confirmed = booking.getStatus() == BookingStatus.CONFIRMED;
```

- [ ] **Step 4: Run the test class to verify both pass**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test -Dtest=BookingNotificationServiceTest
```

Expected: PASS — 2 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git add javabookingapp/src/main/java/com/dev/bookingapp/javabookingapp/service/BookingNotificationService.java javabookingapp/src/test/java/com/dev/bookingapp/javabookingapp/service/BookingNotificationServiceTest.java && git commit -m "feat: booking-details email copy adapts to confirmed vs pending status"
```

---

### Task 4: Frontend — Business type, BookingSettingsPanel, Settings tab

**Files:**
- Modify: `src/types/api.ts` (`Business` interface, after `cancellationNoticeHours`)
- Create: `src/components/BookingSettingsPanel.tsx`
- Test: `src/components/BookingSettingsPanel.test.tsx`
- Modify: `src/pages/DashboardPage.tsx` (`Tab` type ~line 13, `TAB_DESCRIPTIONS` ~line 21, tab buttons ~line 205, panel render ~line 260)

**Interfaces:**
- Consumes: existing `businessesApi.getBusiness(businessId, token)` and `businessesApi.updateBusiness(businessId, request, token)` from `src/api/businesses.ts` — `updateBusiness` is a full-object PUT (backend requires `name` + `email` present), so the panel must send the loaded business spread with the changed flag. Backend JSON property `autoConfirmBookings` from Task 1. This task depends only on backend Task 1 (not Tasks 2–3) and runs in the `bookingsystem-frontend` repo.
- Produces: `Business.autoConfirmBookings?: boolean`; `<BookingSettingsPanel businessId={string} token={string} />` rendered under a new `settings` dashboard tab.

- [ ] **Step 1: Create the feature branch**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git checkout -b feature/auto-confirm-toggle main
```

- [ ] **Step 2: Add the type field**

In `src/types/api.ts`, inside `interface Business`, after `cancellationNoticeHours?: number`:

```ts
  autoConfirmBookings?: boolean
```

- [ ] **Step 3: Write the failing panel tests**

Create `src/components/BookingSettingsPanel.test.tsx`:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as businessesApi from '../api/businesses'
import type { Business } from '../types/api'
import { BookingSettingsPanel } from './BookingSettingsPanel'

vi.mock('../api/businesses')

const business: Business = {
  id: 'b-1',
  name: 'Absolutely Fabulous Hair and Beauty',
  slug: 'absolutelyfabuloushairandbeauty',
  email: 'salon@example.com',
  autoConfirmBookings: true,
}

function renderPanel() {
  return render(<BookingSettingsPanel businessId="b-1" token="tok" />)
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(businessesApi.getBusiness).mockResolvedValue(business)
  vi.mocked(businessesApi.updateBusiness).mockImplementation(
    async (_businessId, request) => request,
  )
})

describe('BookingSettingsPanel', () => {
  it('shows the toggle checked when the business auto-confirms', async () => {
    renderPanel()

    expect(
      await screen.findByLabelText('Automatically confirm new bookings'),
    ).toBeChecked()
  })

  it('shows the toggle unchecked when the business opted out', async () => {
    vi.mocked(businessesApi.getBusiness).mockResolvedValue({
      ...business,
      autoConfirmBookings: false,
    })
    renderPanel()

    expect(
      await screen.findByLabelText('Automatically confirm new bookings'),
    ).not.toBeChecked()
  })

  it('saves the toggled value with the full business object', async () => {
    const user = userEvent.setup()
    renderPanel()

    await user.click(
      await screen.findByLabelText('Automatically confirm new bookings'),
    )
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    await waitFor(() =>
      expect(businessesApi.updateBusiness).toHaveBeenCalledWith(
        'b-1',
        { ...business, autoConfirmBookings: false },
        'tok',
      ),
    )
    expect(await screen.findByText('Settings saved.')).toBeInTheDocument()
  })

  it('shows an error when saving fails', async () => {
    vi.mocked(businessesApi.updateBusiness).mockRejectedValue(
      new Error('boom'),
    )
    const user = userEvent.setup()
    renderPanel()

    await user.click(
      await screen.findByLabelText('Automatically confirm new bookings'),
    )
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(
      await screen.findByText('Failed to save settings.'),
    ).toBeInTheDocument()
  })
})
```

- [ ] **Step 4: Run the tests to verify they fail**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/components/BookingSettingsPanel.test.tsx
```

Expected: FAIL — cannot resolve `./BookingSettingsPanel`.

- [ ] **Step 5: Implement the panel**

Create `src/components/BookingSettingsPanel.tsx` (mirrors `OpeningHoursPanel`'s load/save/error/saved-banner shape):

```tsx
import { useCallback, useEffect, useState } from 'react'
import { ApiClientError } from '../api/client'
import * as businessesApi from '../api/businesses'
import type { Business } from '../types/api'

export function BookingSettingsPanel({
  businessId,
  token,
}: {
  businessId: string
  token: string
}) {
  const [business, setBusiness] = useState<Business | null>(null)
  const [autoConfirm, setAutoConfirm] = useState(true)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [savedMessage, setSavedMessage] = useState(false)

  const loadBusiness = useCallback(async () => {
    setLoading(true)
    setError(null)

    try {
      const loaded = await businessesApi.getBusiness(businessId, token)
      setBusiness(loaded)
      setAutoConfirm(loaded.autoConfirmBookings !== false)
    } catch (err) {
      const message =
        err instanceof ApiClientError
          ? err.message
          : 'Failed to load settings.'
      setError(message)
    } finally {
      setLoading(false)
    }
  }, [businessId, token])

  useEffect(() => {
    loadBusiness()
  }, [loadBusiness])

  async function handleSave() {
    if (!business) return

    setSaving(true)
    setError(null)
    setSavedMessage(false)

    try {
      // Full-object PUT: the backend validates name/email are present,
      // so send the loaded business merged with the change.
      const updated = await businessesApi.updateBusiness(
        businessId,
        { ...business, autoConfirmBookings: autoConfirm },
        token,
      )
      setBusiness(updated)
      setAutoConfirm(updated.autoConfirmBookings !== false)
      setSavedMessage(true)
    } catch (err) {
      const message =
        err instanceof ApiClientError
          ? err.message
          : 'Failed to save settings.'
      setError(message)
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <div className="panel">
        <p>Loading…</p>
      </div>
    )
  }

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h3>Booking settings</h3>
          <p className="panel-subtitle">
            How new bookings from your public booking page behave.
          </p>
        </div>
        <button
          className="btn btn-primary btn-sm"
          onClick={handleSave}
          disabled={saving || !business}
        >
          {saving ? 'Saving…' : 'Save settings'}
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {savedMessage && <div className="success-banner">Settings saved.</div>}

      <label className="schedule-day-toggle">
        <input
          type="checkbox"
          checked={autoConfirm}
          onChange={(e) => {
            setSavedMessage(false)
            setAutoConfirm(e.target.checked)
          }}
        />
        <span className="schedule-day-name">
          Automatically confirm new bookings
        </span>
      </label>
      <p className="panel-subtitle">
        On: customers see “Confirmed ✓” the moment they book — no action
        needed from you. Off: new bookings arrive as requests you confirm
        from the Bookings tab, and customers wait to hear back.
      </p>
    </div>
  )
}
```

- [ ] **Step 6: Run the panel tests to verify they pass**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/components/BookingSettingsPanel.test.tsx
```

Expected: PASS — 4 tests.

- [ ] **Step 7: Wire the Settings tab into the dashboard**

In `src/pages/DashboardPage.tsx`:

Import (with the other component imports, ~line 7):

```tsx
import { BookingSettingsPanel } from '../components/BookingSettingsPanel'
```

Extend the `Tab` type (~line 13):

```tsx
type Tab =
  | 'bookings'
  | 'calendar'
  | 'services'
  | 'opening-hours'
  | 'new-booking'
  | 'photos'
  | 'settings'
```

Add to `TAB_DESCRIPTIONS` (~line 21, after `photos`):

```tsx
  settings:
    'How your bookings behave, like whether new bookings are confirmed automatically.',
```

Add a tab button after the Photos button (~line 210):

```tsx
        <button
          className={`tab ${tab === 'settings' ? 'active' : ''}`}
          onClick={() => setTab('settings')}
        >
          Settings
        </button>
```

Render the panel after the `photos` panel block (~line 262):

```tsx
          {tab === 'settings' && (
            <BookingSettingsPanel businessId={businessId!} token={token!} />
          )}
```

- [ ] **Step 8: Run the full frontend suite and typecheck**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npm test && npx tsc -b
```

Expected: all tests pass (42 existing + 4 new = 46), tsc clean.

- [ ] **Step 9: Commit**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git add src/types/api.ts src/components/BookingSettingsPanel.tsx src/components/BookingSettingsPanel.test.tsx src/pages/DashboardPage.tsx && git commit -m "feat: booking settings panel with auto-confirm toggle"
```

---

### Task 5: Frontend — confirmation screen branches on booking status

**Files:**
- Modify: `src/pages/BookBusinessPage.tsx` (the `if (confirmation)` block, lines 392–423)
- Test: `src/pages/BookBusinessPage.test.tsx`

**Interfaces:**
- Consumes: `confirmation: Booking | null` state already set by all three submit paths; `Booking.status` is already typed (`src/types/api.ts:170`). Backend from Task 2 returns `status: 'CONFIRMED'` when auto-confirm is on.
- Produces: confirmed bookings show eyebrow "Confirmed ✓" and "you're booked" copy; pending bookings keep the exact current "Request sent" copy. The `<h2>Thanks, {firstName}</h2>` heading stays identical in both branches (existing tests assert it).

- [ ] **Step 1: Write the failing tests**

In `src/pages/BookBusinessPage.test.tsx`:

(a) In the existing test `'lets a guest book by entering a 6-digit emailed code'` (~line 330), after the final `await screen.findByText(/thanks, gwen/i)`, add assertions pinning the PENDING copy:

```tsx
    expect(screen.getByText('Request sent')).toBeInTheDocument()
    expect(screen.getByText(/will confirm your appointment/i)).toBeInTheDocument()
```

(b) Add a new test directly after that test:

```tsx
  it('shows instant confirmation when the business auto-confirms', async () => {
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
      status: 'CONFIRMED',
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

    await user.type(screen.getByLabelText('First name'), 'Gwen')
    await user.type(screen.getByLabelText('Last name'), 'Guest')
    await user.type(screen.getByLabelText('Email'), 'gwen@example.com')
    await user.click(screen.getByRole('button', { name: /email me a code/i }))

    await screen.findByText(/we sent a code to/i)
    await user.type(screen.getByLabelText(/6-digit code/i), '123456')
    await user.click(screen.getByRole('button', { name: /confirm booking/i }))

    await screen.findByText(/thanks, gwen/i)
    expect(screen.getByText('Confirmed ✓')).toBeInTheDocument()
    expect(screen.getByText(/you.re booked/i)).toBeInTheDocument()
    expect(screen.queryByText('Request sent')).not.toBeInTheDocument()
    expect(screen.queryByText(/will confirm your appointment/i)).not.toBeInTheDocument()
  })
```

- [ ] **Step 2: Run the file to verify the new test fails**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/BookBusinessPage.test.tsx
```

Expected: FAIL — `'shows instant confirmation when the business auto-confirms'` cannot find `Confirmed ✓` (screen still shows "Request sent" unconditionally). The amended PENDING test passes (it pins existing copy).

- [ ] **Step 3: Implement the branch**

In `src/pages/BookBusinessPage.tsx`, replace the whole `if (confirmation)` block (lines 392–423) with:

```tsx
  if (confirmation) {
    const isConfirmed = confirmation.status === 'CONFIRMED'
    return (
      <div className="panel booking-panel booking-confirmation">
        <div className="confirmation-mark" aria-hidden="true">
          ✓
        </div>
        <div>
          <p className="booking-eyebrow">
            {isConfirmed ? 'Confirmed ✓' : 'Request sent'}
          </p>
          <h2>Thanks, {confirmation.customer.firstName}</h2>
          <p>
            Your appointment with <strong>{business.name}</strong> for{' '}
            <strong>{confirmation.service.name}</strong> on{' '}
            <strong>{formatDateTime(confirmation.startDatetime)}</strong>{' '}
            {isConfirmed ? 'is booked.' : 'has been sent.'}
          </p>
        </div>
        <p className="confirmation-note">
          {isConfirmed ? (
            <>
              You&apos;re booked — there&apos;s nothing else you need to do.
              If anything changes, {business.name} will contact you at{' '}
              <strong>{confirmation.customer.email}</strong>.
            </>
          ) : (
            <>
              {business.name} will confirm your appointment. You&apos;ll hear
              back at <strong>{confirmation.customer.email}</strong>.
              There&apos;s nothing else you need to do.
            </>
          )}
        </p>
        <div className="actions-row">
          <Link to="/book" className="btn btn-secondary">
            Book with another business
          </Link>
          <Link to={`/book/${business.slug}`} className="btn btn-primary">
            Make another booking
          </Link>
        </div>
      </div>
    )
  }
```

(The only changes vs current: eyebrow ternary, "has been sent." → status ternary, confirmation-note ternary. Heading, mark, and action links are untouched.)

- [ ] **Step 4: Run the file to verify all pass**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npx vitest run src/pages/BookBusinessPage.test.tsx
```

Expected: PASS — 13 tests (12 existing + 1 new).

- [ ] **Step 5: Commit**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git add src/pages/BookBusinessPage.tsx src/pages/BookBusinessPage.test.tsx && git commit -m "feat: confirmation screen shows Confirmed vs Request sent by booking status"
```

---

### Task 6: Whole-feature verification (both repos)

**Files:** none created or modified (fixes only if something fails — then fix, re-run, commit the fix in the owning repo).

**Interfaces:**
- Consumes: everything from Tasks 1–5.
- Produces: green suites in both repos, ready for the finishing-a-development-branch flow.

- [ ] **Step 1: Full backend suite**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java/javabookingapp && ./mvnw test
```

Expected: all unit tests pass. The ONLY acceptable failure is `JavabookingappApplicationTests.contextLoads` (needs a live DB — pre-existing/environmental).

- [ ] **Step 2: Full frontend suite, typecheck, production build**

```bash
cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && npm test && npx tsc -b && npm run build
```

Expected: 46 tests pass, tsc clean, Vite build succeeds.

- [ ] **Step 3: Confirm branch state**

```bash
cd /home/regandev/bookingsystemRepos/booking-backend-app-java && git log --oneline main..feature/auto-confirm-toggle && cd /home/regandev/bookingsystemRepos/bookingsystem-frontend && git log --oneline main..feature/auto-confirm-toggle
```

Expected: backend shows 3 commits (Tasks 1, 2, 3); frontend shows 2 commits (Tasks 4, 5). No uncommitted changes in either repo.
