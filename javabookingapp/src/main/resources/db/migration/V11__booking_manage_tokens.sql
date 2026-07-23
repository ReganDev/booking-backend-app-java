-- Plan 3: self-service manage-booking links. One token per booking, stored
-- hashed (same pattern as password_reset_tokens). No expiry column: validity
-- is derived from the booking (usable until the appointment ends).
CREATE TABLE booking_manage_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
