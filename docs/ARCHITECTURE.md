# How the Booking System Works

_Last updated: 2026-07-18_

## The pieces

| Layer | Repo | Tech | Hosted on |
|---|---|---|---|
| App frontend | `bookingsystem-frontend` | React + TypeScript + Vite | Vercel |
| Backend API | `booking-backend-app-java` | Java 21, Spring Boot 4 | Railway |
| Database | (schema in this repo, `db/migration`) | PostgreSQL + Flyway | Supabase |
| Landing page | `booking-landing-page` | Static React site | Separate static host, no API connection |

In production the frontend calls the API with relative `/api/v1/...` paths and
Vercel rewrites them to Railway (`vercel.json`), so the browser never makes a
cross-origin request. Locally, the Vite dev server proxies `/api` to
`localhost:8080` the same way.

The database schema is owned entirely by the Flyway migrations in
`src/main/resources/db/migration`. Hibernate runs with `ddl-auto=validate` and
never alters tables. Point the app at an empty Postgres database and it builds
the schema itself on first startup.

## Accounts and roles

There are four roles, all stored in the single `users` table:

| Role | business_id | How created | Can do |
|---|---|---|---|
| `ADMIN` | null | Seeded on startup from `ADMIN_EMAIL` / `ADMIN_PASSWORD` env vars | Admin console: create and list business accounts |
| `OWNER` | set | By the admin console (or self-registration where enabled) | Everything within their own business |
| `STAFF` | set | Created by an owner (no UI yet) | Business endpoints for their own business |
| `CUSTOMER` | null | Open self-signup at `/signup` | Public booking endpoints only; details prefilled when booking |

Key design points:

- **Business self-registration is gated.** `POST /auth/register` returns 403
  unless `app.registration-enabled` is true (env `REGISTRATION_ENABLED`,
  default off in production, on in dev). Business accounts are normally
  created by the platform admin through the admin console at `/admin`.
- **Customer signup is always open** (`POST /auth/register-customer`).
  Customer accounts have no business attached; a partial unique index keeps
  business-less emails unique.
- **The platform admin has no signup path at all.** It exists only if the
  `ADMIN_EMAIL` / `ADMIN_PASSWORD` env vars are set; the seeder creates it on
  startup if missing. Dev uses `admin@local.dev` / `LocalAdmin123!` from
  `application-dev.properties`.
- **Customers in the `customers` table are not logins.** They are per-business
  contact records created when someone books (deduplicated by email via
  get-or-create). A `CUSTOMER` login is a separate convenience account that
  prefills the booking form; bookings still link to the per-business customer
  record by email.

## Authentication flow

Stateless JWT with rotation-based refresh:

1. **Login** (`POST /auth/login`) returns an access token (15 min) and a
   refresh token (7 days). The access token carries `sub` (user id), `email`,
   `role`, and `businessId` (absent for admin/customer accounts).
2. Every request sends `Authorization: Bearer <accessToken>`.
   `JwtAuthenticationFilter` validates it and loads the user.
3. **When the access token expires** the API returns **401** (an explicit
   `HttpStatusEntryPoint` — the Spring default of 403 would be wrong here).
   The frontend's API client catches the 401, calls `POST /auth/refresh`
   once, stores the new token pair, and retries the request. A single-flight
   guard ensures concurrent 401s share one refresh call, because **refresh
   tokens rotate**: each refresh revokes the old token and issues a new one,
   so a second concurrent refresh attempt would be rejected.
4. If the refresh itself fails, the frontend clears the session and the route
   guards redirect to the login page.
5. **Logout** (`POST /auth/logout`) revokes the refresh token server-side and
   clears local storage.

Refresh tokens are stored hashed (SHA-256) in the `refresh_tokens` table.
Passwords are BCrypt.

### Status code contract

- **401** — no valid access token (missing, expired, malformed). Clients
  should refresh and retry.
- **403** — authenticated but not allowed: wrong business (tenant guard),
  wrong role (`@PreAuthorize`), or registration disabled.

## Authorization layers

Two mechanisms work together:

1. **Route rules** (`SecurityConfig`): `/api/v1/auth/**`, `/api/v1/public/**`
   and `/actuator/health` are open; everything else requires a valid JWT.
2. **Tenant guard** (`TenantAccessInterceptor`): any route with a
   `{businessId}` path variable is checked against the `businessId` in the
   caller's token. A mismatch is 403. Accounts without a business (admin,
   customer) are always denied on these routes — the admin uses its own
   `/api/v1/admin/**` endpoints (guarded by `@PreAuthorize("hasRole('ADMIN')")`),
   and customers use only the public endpoints.

## Booking and availability logic

- A **service** defines what can be booked (duration, price). A **schedule**
  defines opening hours per weekday, with optional breaks. **Blocked times**
  are one-off exclusions.
- Available slots for a day are computed in `AvailabilityService`: start at
  the day's opening time in the business's timezone, step by the business's
  slot duration, and keep slots that fit before closing and don't overlap a
  break, a blocked time, or an existing booking. Notice hours (minimum
  warning) and advance days (maximum look-ahead) trim the window.
- The customer calendar uses `GET /public/.../availability/days?month=` which
  runs the same computation for each day of a month, so a day is only
  selectable if a real slot exists.
- **Public bookings are validated against this availability** — a requested
  time must exactly match a computed slot. Owner-created dashboard bookings
  skip the schedule rules deliberately (walk-ins outside hours) and are only
  checked for clashes.
- Prices are snapshotted onto the booking at creation, so later service price
  changes don't rewrite history.
- Booking statuses: `PENDING` → `CONFIRMED` / `CANCELLED` / `COMPLETED` /
  `NO_SHOW`, managed from the dashboard.

## Environments

| | Local dev | Production |
|---|---|---|
| Profile | `dev` (must be passed: `-Dspring-boot.run.profiles=dev`) | `prod` (default) |
| Database | Local Postgres `bookingapp_dev` | Supabase via `DATABASE_URL` (session pooler, port 5432) |
| Business self-registration | On | Off unless `REGISTRATION_ENABLED=true` |
| Admin account | `admin@local.dev` / `LocalAdmin123!` | From `ADMIN_EMAIL` / `ADMIN_PASSWORD` |
| Enquiry email | 503 unless `RESEND_API_KEY` set | Resend → `ENQUIRY_TO` (default graysitequeries@gmail.com) |

Backend env vars used in production: `DATABASE_URL`, `JWT_SECRET`, `PORT`
(Railway-injected), `REGISTRATION_ENABLED`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`,
`RESEND_API_KEY`, `RESEND_FROM`, `ENQUIRY_TO`.

Run locally:

```bash
cd javabookingapp
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Frontend: `npm run dev` (port 5173, proxies `/api` to 8080).
