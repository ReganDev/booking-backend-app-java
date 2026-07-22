-- V9__booking_otp_sessions.sql
-- Pending guest bookings awaiting email OTP confirmation.
CREATE TABLE booking_otp_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,

    -- Booking payload, applied when the code is verified
    start_datetime TIMESTAMP WITH TIME ZONE NOT NULL,
    customer_notes TEXT,
    email_reminder BOOLEAN NOT NULL DEFAULT TRUE,
    sms_reminder BOOLEAN NOT NULL DEFAULT FALSE,

    -- True when this flow created the user; drives the claim-account email
    new_account BOOLEAN NOT NULL DEFAULT FALSE,

    -- OTP state
    -- Hibernate maps Java String as VARCHAR. Keep the fixed 64-character limit
    -- while using the JDBC type Hibernate validates for this field.
    code_hash VARCHAR(64) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_booking_otp_sessions_user
    ON booking_otp_sessions(user_id, created_at DESC);
