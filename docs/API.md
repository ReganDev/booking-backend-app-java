# Booking System API Reference

_Last updated: 2026-07-18_

Base URL: `/api/v1` (production: `https://app.bookingbase.co.uk/api/v1`, served by the dashboard host's `/api/*` rewrite to Railway; the Railway origin URL is an implementation detail and is not the public API address).

All request and response bodies are JSON. Authenticated endpoints expect
`Authorization: Bearer <accessToken>`.

**Auth levels** used below:

- **Public** — no token needed.
- **Business** — token whose `businessId` matches the `{businessId}` in the
  path (OWNER/STAFF/ADMIN of that business). Mismatches and business-less
  tokens get 403.
- **Admin** — token with the platform `ADMIN` role.

**Error shape** (all non-2xx responses):

```json
{ "timestamp": "...", "status": 409, "error": "Conflict", "message": "Email is already registered" }
```

Validation failures (400) add a `fieldErrors` map keyed by field name.

**Status codes**: `401` means no/expired credentials (refresh and retry);
`403` means authenticated but not allowed; `409` means a conflict (duplicate
email, slot taken); `503` means a dependent service (email) is unavailable.

---

## Authentication

### POST /auth/register — create business + owner *(gated)*
Returns 403 unless `app.registration-enabled` is on. Normally use the admin
console instead.

```json
{ "businessName": "Fab Hair", "firstName": "Jane", "lastName": "Smith",
  "email": "jane@fabhair.uk", "password": "min8chars", "phone": "optional",
  "timezone": "Europe/London (optional)", "currency": "GBP (optional)" }
```
**201** → auth response (below). **409** if email exists.

### POST /auth/register-customer — create customer account *(public, always open)*
```json
{ "firstName": "Cathy", "lastName": "Client", "email": "cathy@mail.com",
  "password": "min8chars", "phone": "optional" }
```
**201** → auth response with `"business": null` and `role: "CUSTOMER"`.

### POST /auth/login *(public)*
```json
{ "email": "...", "password": "..." }
```
**200** → auth response:
```json
{ "accessToken": "jwt", "refreshToken": "jwt", "tokenType": "Bearer",
  "expiresIn": 900, "user": { "id": "...", "role": "OWNER", "...": "..." },
  "business": { "id": "...", "name": "...", "slug": "..." } }
```
`business` is null for ADMIN and CUSTOMER accounts. **401** on bad credentials.

### POST /auth/refresh *(public)*
```json
{ "refreshToken": "jwt" }
```
**200** → fresh auth response. The old refresh token is revoked (rotation) —
never reuse it. **401** if expired/revoked.

### POST /auth/logout *(public)*
```json
{ "refreshToken": "jwt" }
```
**204**. Revokes the refresh token.

---

## Public booking endpoints

### GET /public/businesses
Active businesses, sorted by name. **200** → `[Business]`.

### GET /public/businesses/slug/{slug}
One active business by its URL slug. **404** if unknown/inactive.

### GET /public/businesses/{businessId}/services
Active services for a business. **200** → `[Service]`.

### GET /public/businesses/{businessId}/availability?serviceId=&date=YYYY-MM-DD
Bookable time slots for one day.
**200** → `[{ "startDatetime": "2026-07-19T09:00:00+01:00", "endDatetime": "..." }]`
Empty when closed, fully booked, in the past, or beyond the advance window.

### GET /public/businesses/{businessId}/availability/days?serviceId=&month=YYYY-MM
Days in a month with at least one bookable slot (feeds the booking calendar).
**200** → `["2026-07-19", "2026-07-26"]`

### POST /public/businesses/{businessId}/bookings
Create a booking as a customer. The start time must exactly match an
available slot.
```json
{ "serviceId": "...", "startDatetime": "2026-07-19T09:00:00+01:00",
  "customerNotes": "optional",
  "customer": { "firstName": "...", "lastName": "...", "email": "...", "phone": "optional" } }
```
**201** → Booking (status `PENDING`). Customer records are deduplicated per
business by email. **409** if the slot was taken meanwhile.

### POST /public/enquiry
Send a contact enquiry to the site owner (via Resend).
```json
{ "name": "...", "email": "...", "businessName": "optional", "message": "..." }
```
**202** on send. **503** with a friendly message if email is not configured.

---

## Business endpoints (auth: Business)

All under `/businesses/{businessId}/...`; the token's `businessId` must match.

### Business profile
| Method | Path | Notes |
|---|---|---|
| GET | `/businesses/{businessId}` | Profile |
| GET | `/businesses/slug/{slug}` | Lookup by slug |
| PUT | `/businesses/{businessId}` | Update profile/settings (timezone, buffer, notice hours, etc.) |

### Services
| Method | Path | Notes |
|---|---|---|
| GET | `/services` | All services |
| GET | `/services/active` | Active only |
| GET | `/services/{serviceId}` | One |
| POST | `/services` | `{ name, durationMinutes, price?, description?, color?, isActive }` |
| PUT | `/services/{serviceId}` | Update |
| DELETE | `/services/{serviceId}` | Delete |

### Bookings
| Method | Path | Notes |
|---|---|---|
| GET | `/bookings` | Paged (`?page=&size=`) |
| GET | `/bookings/range?start=&end=` | ISO datetimes; feeds the dashboard calendar |
| GET | `/bookings/staff/{staffId}?start=&end=` | Per staff member |
| GET | `/bookings/{bookingId}` | One |
| POST | `/bookings` | `{ customerId, serviceId, startDatetime, staffId?, customerNotes? }` — clash-checked but not limited to opening hours (walk-ins allowed) |
| PATCH | `/bookings/{bookingId}/status` | `{ status: "CONFIRMED" \| "CANCELLED" \| "COMPLETED" \| "NO_SHOW", cancellationReason? }` |
| PATCH | `/bookings/{bookingId}/reschedule` | `{ newStartTime }` — clash-checked |

### Customers (contact records, not logins)
| Method | Path | Notes |
|---|---|---|
| GET | `/customers` | Paged |
| GET | `/customers/search?query=` | Name/email search |
| GET | `/customers/{customerId}` | One |
| POST | `/customers` | 409 if email exists for this business |
| POST | `/customers/get-or-create` | Returns existing by email, or creates |
| PUT | `/customers/{customerId}` | Update |
| DELETE | `/customers/{customerId}` | Delete |

### Schedules (opening hours)
| Method | Path | Notes |
|---|---|---|
| GET | `/schedules` | Business-wide hours (one row per open weekday, with breaks) |
| GET | `/schedules/staff/{userId}` | Per-staff hours |
| POST | `/schedules` | Upsert one weekday: `{ dayOfWeek: "MONDAY", startTime: "09:00", endTime: "17:00", isActive, breaks: [{ startTime, endTime, label? }] }` |
| POST | `/schedules/staff/{userId}/week` | Replace a staff member's week |
| DELETE | `/schedules/{scheduleId}` | Remove a day |

### Users (staff)
| Method | Path | Notes |
|---|---|---|
| GET | `/users` | All users in the business |
| GET | `/users/staff` | Bookable staff |
| GET | `/users/{userId}` | One |
| PUT | `/users/{userId}` | Update |
| DELETE | `/users/{userId}` | Deactivate/remove |

---

## Admin endpoints (auth: Admin)

### GET /admin/businesses
Every business, active or not. **200** → `[Business]`.

### POST /admin/businesses
Create a business and its owner login in one call (same body as
`/auth/register`). **201** →
```json
{ "business": { "id": "...", "name": "...", "slug": "..." },
  "owner": { "id": "...", "email": "...", "role": "OWNER" } }
```
**409** if the email is taken. Non-admin tokens get 403.

---

## Health

`GET /actuator/health` *(public)* → `{ "status": "UP" }`. Used by the
frontend proxy checks and uptime monitoring.
