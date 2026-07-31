# Mobile Services: Customer Address + Drive Distance

## Context

BookingBase currently assumes customers travel to the business. Businesses that visit the customer (mobile hairdressers, therapists, cleaners…) need the customer's address collected at booking time. Agreed with user:

- **Per-service flag** ("Mobile visit — happens at the customer's address"), not per-business.
- Customer must enter a UK-style address (line1, line2 optional, city, postcode) when booking a flagged service — in both the authenticated and guest-OTP public flows.
- Owner sees **driving distance + time** on the dashboard booking card ("7.2 mi · ~18 min drive"), computed with free keyless services: postcodes.io (geocoding) + public OSRM demo server (routing). Best-effort: failures never block a booking; card falls back to address + Google Maps directions link.
- **No service-area radius enforcement** (explicitly deferred).

Repos: backend `booking-backend-app-java/javabookingapp` (Spring Boot 4, branch off `main`, next Flyway migration is **V14**), frontend `bookingsystem-frontend` (currently on `feature/existing-customer-picker` — build frontend work on that branch's reworked `NewBookingPanel`).

## Design decisions

- **D1 — Address as flat nullable columns on `bookings`** (`address_line1/2`, `address_city`, `address_postcode`) + `distance_meters`, `duration_seconds` (INTEGER). Same four address columns on `booking_otp_sessions` (guest pending payload persists there between start/verify). No `Customer` changes.
- **D2 — Business origin cached on `businesses`**: `latitude`, `longitude`, `geocoded_postcode`. Lazily resolved; cache valid while `geocoded_postcode` matches current normalised `postal_code` — self-invalidating when owner edits address, no update hook needed.
- **D3 — Distance computed async after commit.** `BookingService.create()` publishes `BookingCreatedEvent(bookingId)` when a postcode is present; listener is `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`. Requires new `@EnableAsync` config (none exists). Rationale: avoids external HTTP inside the open booking tx and the known rollback-only trap (cf. `manageTokenService.issueLink` at `BookingService.java:109-115`). Whole listener body try/catch-logged (async exceptions are silent otherwise).
- **D4 — Conditional validation in service layer**: static helper on `BookingService` (require line1/city/postcode iff `service.requiresCustomerAddress`; normalise postcode: trim/uppercase/single inner space; lenient UK regex `^[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}$`; `BadRequestException` otherwise). Called from `createPublicBooking` and `GuestBookingService.start` (before OTP email). DTOs get only `@Size` caps.
- **D5 — Owner manual bookings: address optional**, no enforcement; event fires if postcode present.
- **D6 — Flat DTO fields matching entity names** so MapStruct auto-maps: `requiresCustomerAddress` on `ServiceRequest/ServiceResponse` (public services endpoint already returns `ServiceResponse` → wizard gets flag for free); address + distance fields on `BookingResponse`.

## Tasks

### Task 1 — Migration V14 (backend)
New `db/migration/V14__mobile_service_customer_address.sql`: `services.requires_customer_address BOOLEAN NOT NULL DEFAULT FALSE`; the six booking columns; four `booking_otp_sessions` address columns; three `businesses` geo columns (`latitude/longitude DOUBLE PRECISION`, `geocoded_postcode VARCHAR(10)`). Comment header per V9/V10 style.

### Task 2 — Service flag (backend)
`entity/Service.java` (`@Builder.Default ... = false`), `ServiceRequest`, `ServiceResponse`. In `ServiceService.create()` add explicit null-guard next to the existing `isActive` one (MapStruct bypasses `@Builder.Default`; column NOT NULL). **New** `ServiceServiceTest.java` (none exists — model on `BusinessServiceTest`): default-false on null, round-trip, null-on-update leaves unchanged.

### Task 3 — Booking address plumbing (backend)
- `entity/Booking.java`: 4 address + 2 distance fields. `entity/BookingOtpSession.java`: 4 address fields.
- `BookingRequest`, `PublicBookingRequest`, `GuestBookingStartRequest`: 4 address fields with `@Size` (255/255/100/10). `BookingResponse`: 4 + distance fields.
- `mapper/BookingMapper.java`: add `@Mapping(target="distanceMeters", ignore=true)` / `durationSeconds` to `toEntity` **and** `updateEntity` (protects distance on owner edits; keeps unmapped warnings clean).
- `BookingService`: static `validateCustomerAddress(...)` + `normalizePostcode(...)`; call validator in `createPublicBooking` after service checks; copy fields into internal `BookingRequest`; normalise in `create()` (owner path).
- `GuestBookingService`: `start()` validates before user-create/OTP email, persists address on session; `verify()` copies session address into reconstructed `PublicBookingRequest`.
- Extend `BookingServiceTest` (missing/invalid address → 400; normalisation `"sw1a1aa"` → `"SW1A 1AA"`; unflagged service unaffected) and `GuestBookingServiceTest` (reject before email — verify emailSender never called; session persists address — ArgumentCaptor; verify forwards address).

### Task 4 — Geo clients + async distance (backend)
Follow `ResendEmailSender` template (self-built `RestClient`, `@Value` config, guard method) + ~2s/3s timeouts + `User-Agent: BookingBase/1.0 (bookingbase.co.uk)`.
- **New** `config/AsyncConfig.java` (`@EnableAsync`).
- **New** `service/PostcodesIoClient.java` — `GET /postcodes/{postcode}` → `Optional<Coordinates>`; base URL `app.geo.postcodes-base-url:https://api.postcodes.io`.
- **New** `service/OsrmClient.java` — `GET /route/v1/driving/{fromLon},{fromLat};{toLon},{toLat}?overview=false`, require `code=="Ok"`; **lon,lat order — unit-test the built URL**; base `app.geo.osrm-base-url:https://router.project-osrm.org`.
- **New** `service/BookingCreatedEvent.java` (record), **new** `service/BookingDistanceService.java` (listener per D3: load booking → origin via D2 cache (save business on refresh) → geocode customer → route → save distance; any empty Optional → warn + return; gated by `app.geo.enabled:true`).
- `BookingService.create()`: publish event after save iff postcode present (covers owner, authenticated, guest-verify paths).
- Properties: `app.geo.*` in `application.properties` + env-overridable in `application-prod.properties` (`${GEO_ENABLED:true}` style).
- **New** `BookingDistanceServiceTest` (happy path; cache hit → one geocode call; stale postcode → re-geocode+save; failure → null distance, no throw; disabled → no calls). Extend `BookingServiceTest` for event publication. Client tests via whatever RestClient idiom `SupabaseStorageServiceTest` uses (else `MockRestServiceServer`).

### Task 5 — Services UI: checkbox + badge (frontend)
`types/api.ts` (`requiresCustomerAddress?` on `Service`/`ServiceRequest`); `DashboardPage.tsx` `ServicesPanel` (~line 269): state, seed in open-create/edit forms, include in submit payload, checkbox "Mobile visit — this service happens at the customer's address", "Mobile" badge on flagged list items.

### Task 6 — Dashboard card: address + distance (frontend)
`types/api.ts` `Booking` fields; `components/BookingCard.tsx`: address block when present; distance line `(m/1609.344).toFixed(1)` mi · `Math.round(s/60)` min when `distanceMeters != null`; always a Directions link `https://www.google.com/maps/dir/?api=1&destination=<encoded address>` (`target="_blank" rel="noopener noreferrer"`); graceful absence. Extend `BookingsPanel.test.tsx`.

### Task 7 — Public wizard address collection (frontend)
`pages/BookBusinessPage.tsx`: `address` state; add to `BookingDraft` (~45), draft save effect (~131-146) + load (~112-125), tolerating old drafts (matters: login round-trip restores from draft); `needsAddress = selectedService?.requiresCustomerAddress === true`; "Mobile — at your address" hint on step-1 picker; address section in **both** step-4 forms (authenticated ~640 — editable unlike readOnly identity fields; guest ~808) with proper `autoComplete` attrs + required + UK pattern; include trimmed fields in `handleSubmit` (~250) and `handleGuestStart` (~299) payloads. `types/api.ts`: fields on `PublicBookingRequest`/`GuestBookingStartRequest`. Extend `BookBusinessPage.test.tsx` (inputs shown iff flagged, both forms; guest payload; draft round-trip).

### Task 8 — Confirmation screen (frontend, fold into Task 7)
Echo address on confirmation ("We'll come to: …") when present.

### Task 9 — Owner manual booking, optional address (frontend, droppable)
`components/NewBookingPanel.tsx` (the `feature/existing-customer-picker` version): optional address section when selected service flagged; include in create payload. Extend `NewBookingPanel.test.tsx`.

## Verification

1. `./mvnw test` (context load validates V14 vs Hibernate mappings).
2. Dev boot against local Postgres → Flyway applies V14.
3. Curl walk-through: flag a service; public services response shows flag; booking without address → 400; with real postcodes (e.g. `SW1A 1AA` → `M1 1AE`) → 201 and distance populated within seconds; `businesses` row has cached lat/lng.
4. Guest path: start (with address) → verify → booking has address + async distance.
5. Failure modes: unreachable OSRM URL → booking succeeds, card shows address + link only; `app.geo.enabled=false` → zero external calls; unflagged service regression-checked.
6. Frontend: `npm test`; manual wizard run (guest + signed-in incl. login round-trip draft restore); dashboard card shows distance; ServicesPanel flag persists.

## Risks (accepted / flagged)

- OSRM demo server has no SLA — mitigated by timeouts, UA header, configurable base URL.
- `@Async`+`@TransactionalEventListener`+`REQUIRES_NEW` combo: listener must be public bean method, not self-invoked; smoke-test once in dev.
- Customers see their own address and the distance via shared `BookingResponse` (`ManageBookingResponse`, customer bookings endpoints) — deemed harmless; no second DTO for now.
- postcodes.io = postcode-centroid precision; "~" in UI copy covers it.
- Confirm no in-flight branch claims V14 before merging (backend branches from `main`).

## Post-approval process note

Brainstorming flow: after approval, save this design as a spec to `booking-backend-app-java/docs/superpowers/specs/2026-07-27-mobile-service-address-design.md`, commit it, then proceed via superpowers:writing-plans / subagent-driven execution.
