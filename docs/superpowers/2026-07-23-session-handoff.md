# Session Handoff — 2026-07-23

Context capsule for the next session. Read this + the spec, then write Plan 2's
implementation plan (superpowers:writing-plans) and execute it
(superpowers:subagent-driven-development).

## Where the project stands

**Goal:** BookingBase is a small-business booking SaaS (early free users). Agreed
strategy: conversion-first — fix "customers don't book" before features/billing.
Spec with all approved decisions: `docs/superpowers/specs/2026-07-22-booking-conversion-design.md`.

**Plan 1 — Guest OTP booking: SHIPPED 2026-07-23.** Merged to `main` in both
repos and pushed (backend `49cf333`, frontend `e4d9886`); Railway + Vercel
auto-deployed. Plan document (all 8 tasks, as executed):
`docs/superpowers/plans/2026-07-22-guest-otp-booking.md`.

What exists now on top of the original codebase:
- `booking_otp_sessions` table (V9), `BookingOtpSession` entity + repository
  (with `incrementAttempts` modifying query)
- `GuestBookingService` (start / verify / resend; SHA-256-hashed 6-digit codes,
  10-min expiry, 5-attempt cap, 60s resend interval),
  `BookingOtpAttemptRecorder` (REQUIRES_NEW so failed-attempt counts survive
  the wrong-code rollback)
- `PasswordResetService.issueClaimLink(User)` — "set a password" email for
  guest-created accounts, reuses the reset-token flow
- Public endpoints on `PublicController`: `POST /api/v1/public/bookings/start`
  (200), `/verify` (201), `/resend` (204)
- Frontend: `startGuestBooking`/`verifyGuestBooking`/`resendGuestBookingCode`
  in `src/api/public.ts`; `BookBusinessPage` step 4 is now guest details form →
  inline OTP entry (sign-in wall removed; signed-in path unchanged;
  `resetOtpSession()` clears pending sessions on any wizard navigation)

**Deploy sanity checks possibly still outstanding** (were suggested, not
confirmed done): Railway logs show Flyway applied V9; one live guest booking
end-to-end (code arrives via Resend).

## Decisions made this round (don't re-litigate)

- Guest flow = inline email OTP (not magic link / pure guest); OTP also sets
  `email_verified = true`; existing accounts never overwritten; business-account
  emails rejected with "please sign in"
- `code_hash` is VARCHAR(64) not CHAR (Hibernate `ddl-auto=validate`; V7 precedent)
- Attempt counting via REQUIRES_NEW recorder (user-approved over noRollbackFor)
- Slot-conflict during verify → full rollback (session stays usable) is
  intended; frontend returns to step 3 on 409 in all three submit paths
- Per-IP rate limiting deferred to Plan 5 (per-email limits shipped)
- Test caveat: backend `JavabookingappApplicationTests.contextLoads` fails
  locally without a live DB — pre-existing/environmental, ignore it

## Plan 2 — Auto-confirm toggle (NEXT UP)

Spec section 2. Scope agreed with the user:

1. **Migration V10**: `ALTER TABLE businesses ADD COLUMN auto_confirm_bookings
   BOOLEAN NOT NULL DEFAULT TRUE;` (existing businesses get auto-confirm ON via
   the default — this was an explicit user decision)
2. **`Business` entity**: add `autoConfirmBookings` Boolean field,
   `@Builder.Default = true`, mapped like siblings (`bookingAdvanceDays`,
   `cancellationNoticeHours` show the pattern at `entity/Business.java:70,78`)
3. **`BookingService.create()`** (`service/BookingService.java:174`): status
   becomes `business.getAutoConfirmBookings() ? BookingStatus.CONFIRMED :
   BookingStatus.PENDING` — enum already has both values, no enum change
4. **`BusinessResponse` / `BusinessRequest` DTOs + mapper**: expose the flag so
   owners can read/update it (check how the other booking-settings fields flow
   through `BusinessController` update)
5. **Owner dashboard toggle**: no settings UI exists yet in `DashboardPage.tsx`
   (801 lines) — follow the existing dashboard panel pattern
   (`components/OpeningHoursPanel.tsx`, `components/PhotosPanel.tsx`, both have
   tests) to add a small booking-settings panel with the toggle: "Automatically
   confirm new bookings" + helper text on the trade-off
6. **Customer-facing outcome copy**: `BookBusinessPage` confirmation screen
   currently says "Request sent / will confirm" unconditionally — branch on the
   returned booking's `status` (`CONFIRMED` → "Confirmed ✓ — you're booked";
   `PENDING` → current copy). Public `Business` type may need the flag only if
   the UI wants to foreshadow it earlier; the booking response status is enough
   for the confirmation screen
7. **Spec deviation to honour**: spec says the booking-details email becomes
   always-on (it will carry the manage link in Plan 3). Decide in Plan 2's
   brainstorm whether to flip it now or leave with Plan 3 — leaving it to
   Plan 3 is the smaller change and loses nothing yet

Estimated shape: ~5 tasks (migration+entity → service+DTO → dashboard panel →
frontend confirmation copy → verification), same TDD/review loop as Plan 1.

## Remaining roadmap after Plan 2

- **Plan 3**: manage-booking links + My Bookings page (cancel + reschedule;
  session auth preferred, hashed email-link token fallback; cancel cutoff via
  existing `cancellation_notice_hours`)
- **Plan 4**: share kit (copy link, client-side QR via `qrcode` npm package,
  Instagram/WhatsApp/Google snippets) — frontend only
- **Plan 5**: hardening/code-review backlog. Itemised list (from per-task +
  final reviews): per-IP rate limiting; session-consume atomicity
  (`UPDATE ... WHERE consumed_at IS NULL`); duplicate-user race in `start()`
  (catch DataIntegrityViolation, re-lookup); resend() persists new hash before
  send confirmed; start/resend interaction allows ~2 emails/min per address;
  OTP expiry UX (client countdown, expired state — frontend never uses
  `expiresAt`); guest reminder checkboxes missing from guest form; 503 copy
  says "verification email" for OTP failures; account-enumeration wording in
  start(); `businesses.timezone` default `'United Kingdom/London'` is invalid
  IANA; `BookingService.updateStatus()` lacks status-transition validation;
  booking conflict-check read-then-write race; V9 index naming; no integration
  test proving REQUIRES_NEW survives rollback

## Process that worked (repeat it)

Brainstorm (skill) → spec → per-plan writing-plans → subagent-driven-development
(fresh implementer per task: haiku for transcription tasks, sonnet for
integration; sonnet reviewers; opus final whole-branch review) → two user
decisions were escalated (CHAR→VARCHAR, REQUIRES_NEW) — plan-mandated defects
go to the user, everything else fix-and-re-review. Ledger:
`.superpowers/sdd/progress.md` (git-ignored; survives locally only).
