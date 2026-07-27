-- Mobile services: per-service "requires customer address" flag, customer
-- address + computed drive distance on bookings, address on pending guest
-- OTP sessions, and a cached geocode of the business origin. Distance
-- columns are best-effort (nullable) — bookings never fail because of geo.

ALTER TABLE services
    ADD COLUMN requires_customer_address BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE bookings
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN address_city VARCHAR(100),
    ADD COLUMN address_postcode VARCHAR(10),
    ADD COLUMN distance_meters INTEGER,
    ADD COLUMN duration_seconds INTEGER;

ALTER TABLE booking_otp_sessions
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN address_city VARCHAR(100),
    ADD COLUMN address_postcode VARCHAR(10);

-- Cached origin geocode; valid while geocoded_postcode matches the business's
-- current normalised postal_code, so editing the address self-invalidates it.
ALTER TABLE businesses
    ADD COLUMN latitude DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD COLUMN geocoded_postcode VARCHAR(10);
