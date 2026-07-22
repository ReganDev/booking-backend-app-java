# Booking Conversion Design — Guest OTP Booking, Auto-Confirm, Manage Links, Share Kit

**Date:** 2026-07-22
**Status:** Approved by owner (pending spec review)
**Goal:** Fix the customer-side conversion leak so businesses on the platform get more completed bookings — the core sellable outcome for the SaaS.

## Problem

The current flow loses customers at the final step: after picking a service, day,
and time, a customer must create an account **and** verify their email before
booking (`BookBusinessPage.tsx` step 4; enforced by
`@PreAuthorize("hasRole('CUSTOMER') and principal.emailVerified == true")` in
`PublicController`). Even then, the booking is only a request the business must
manually confirm. Competitors (Booksy, Fresha, Setmore) offer near-guest
checkout with instant confirmation.

## Scope (agreed)

1. **Guest booking via inline email OTP** — no password, no page exit.
2. **Per-business auto-confirm toggle** — default ON; instant "Confirmed ✓".
3. **Self-service manage-booking** — cancel + reschedule; session auth for
   signed-in customers, secure email-link token as fallback for guests.
4. **Share kit** — copy link, QR code, and social snippets on the owner dashboard.
5. **Code review** of all three repos (backend, app frontend, landing page);
   fixes woven in where they touch the same files, remainder documented.

Out of scope this round: Stripe billing, SMS reminders, staff accounts,
calendar sync, embeddable widget.

## 1. Guest booking with inline email OTP

### Customer experience

- Steps 1–3 of the wizard (service → day → time) are unchanged.
- Step 4, signed-in verified customer: unchanged (prefilled, one click).
- Step 4, everyone else: a normal details form (first name, last name, email,
  phone optional, notes). Submitting sends a 6-digit code to the email; the UI
  swaps to a single code input ("We sent a code to jo@…"). Entering the correct
  code places the booking immediately.

### Backend

- New public endpoints on `PublicController`:
  - `POST /api/v1/public/bookings/start` — validates slot availability and
    details, creates or loads a passwordless `CUSTOMER` user by normalized
    email (unique via `ux_users_email_lower`), sends the OTP, returns a
    short-lived `bookingSessionId`.
  - `POST /api/v1/public/bookings/verify` — checks the OTP, re-validates the
    slot, creates the booking.
- OTP storage: a new `booking_otp` table (separate from
  `email_verification_tokens` — different lifecycle and payload) following the
  same hashing pattern: code stored hashed; 10-minute expiry; max 5 attempts;
  resend limited to 1/minute. Delivery via the existing Resend sender.
- A correct OTP also sets `email_verified = true` on the user — returning
  customers who later log in are already verified. Existing accounts are never
  duplicated; the OTP simply proves ownership.
- Follow-up email includes a "set a password to manage your bookings" claim
  link, reusing the existing password-reset flow.
- The old authenticated booking endpoint keeps its `@PreAuthorize` gate until
  the frontend fully migrates. Both new endpoints are rate-limited per IP and
  per email.
- Spam protection retained: every booking still has a proven-real email.

## 2. Per-business auto-confirm

- Migration `V9__auto_confirm_and_manage_links.sql`: add
  `auto_confirm_bookings BOOLEAN NOT NULL DEFAULT TRUE` to `businesses`.
- `BookingService.create()`: status becomes `CONFIRMED` when the business has
  auto-confirm on; otherwise stays `PENDING` (existing request/approve flow
  unchanged for businesses that opt out). No `booking_status` enum change.
- Owner dashboard: settings toggle "Automatically confirm new bookings" with
  helper text on the trade-off.
- Customer confirmation screen and email adapt: "Confirmed ✓ — you're booked"
  vs "Request sent — the business will confirm."
- The booking-details email becomes **always-on** (currently opt-in via
  checkbox) because it now carries the manage link.

## 3. Manage-booking (cancel + reschedule)

### Access model — one page, two ways in

- **Signed-in customers**: JWT session auth; no token needed. New **"My
  bookings"** page (upcoming/past) with cancel and reschedule actions:
  - `GET /api/v1/customers/me/bookings`
  - `POST /api/v1/customers/me/bookings/{id}/cancel`
  - `POST /api/v1/customers/me/bookings/{id}/reschedule`
- **Email link fallback** (primarily for OTP guests with no password): every
  booking email includes `/manage/booking/<token>` — random 32-byte token,
  stored hashed (same pattern as verification tokens), tied to the booking,
  valid until the appointment ends. If the visitor already has a valid session
  owning the booking, the session is used; the token is only checked otherwise.

### Rules

- **Cancel** allowed until `cancellation_notice_hours` (existing column on
  `businesses`) before the start. Inside the cutoff, the page shows the
  business phone/email with "contact the business to cancel."
- **Reschedule** reuses wizard steps 2–3 for slot picking and the existing
  `BookingService.reschedule()` logic via new endpoints, subject to the same
  notice-hours rules.
- Business is notified by email on customer cancels/reschedules; freed slots
  become available immediately.
- Public token endpoints: `GET /api/v1/public/manage/{token}`,
  `POST .../cancel`, `POST .../reschedule`. Rate-limited; lookup by hash.

## 4. Share kit (owner dashboard)

- New "Share your page" panel:
  - Copy `/book/<slug>` link (one-click copy).
  - QR code generated client-side (`qrcode` npm package), displayed and
    downloadable as PNG.
  - Prewritten snippets for Instagram bio, WhatsApp, and Google Business
    Profile, each with a copy button.
- Frontend-only; no schema or API changes.

## 5. Code review workstream

- **Backend** (Spring Boot 4 / Java 21, checked against current docs via
  context7). Candidates already spotted:
  - `businesses.timezone` default `'United Kingdom/London'` is not a valid
    IANA zone (V2 fixed existing rows; the default remains wrong).
  - `BookingService.updateStatus()` performs no status-transition validation
    (e.g. a cancelled booking can be flipped back to confirmed).
  - Booking conflict checks are read-then-write and can race under
    concurrency; consider a DB-level exclusion constraint or serializable
    retry.
- **Frontend** (React 19, React Router 7): booking wizard, API client, auth
  context, wizard accessibility.
- Triage: findings touching feature files are fixed within the plan; the rest
  are written up as a findings list for later.

## Delivery order (each independently shippable)

1. Guest OTP booking (largest conversion win)
2. Auto-confirm toggle
3. Manage-booking (My bookings page + email link)
4. Share kit
5. Code-review fixes (woven throughout where they touch the same files)

## Error handling & edge cases

- Slot taken between `start` and `verify`: verify re-validates and returns a
  conflict; UI returns to step 3 with slots refreshed (existing behaviour).
- OTP expiry/attempt exhaustion: clear error, offer resend (rate-limited).
- Email delivery failure on `start`: surfaced to the customer as a retryable
  error; no booking session is leaked.
- Timezone display: all customer-facing times remain in the browser's local
  timezone as today.

## Testing

- Backend: unit tests for OTP lifecycle (expiry, attempts, resend limits,
  verified-flag side effect), auto-confirm status logic, cancel-cutoff
  enforcement, manage-token auth-vs-session precedence; integration tests for
  the new public endpoints.
- Frontend: component tests for the new step-4 guest form + OTP input, manage
  page states (cancellable, inside cutoff, expired token), My bookings page,
  and share-kit copy actions — following the existing Vitest + Testing
  Library patterns.
