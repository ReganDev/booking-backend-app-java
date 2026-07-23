-- Plan 2: per-business auto-confirm toggle. Existing businesses get
-- auto-confirm ON via the default (explicit product decision).
ALTER TABLE businesses
    ADD COLUMN auto_confirm_bookings BOOLEAN NOT NULL DEFAULT TRUE;
