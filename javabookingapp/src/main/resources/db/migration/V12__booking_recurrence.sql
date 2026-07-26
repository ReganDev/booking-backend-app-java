-- Plan: recurring bookings. A standing appointment ("every Tuesday at 2pm") is
-- stored as N real rows in `bookings` linked by `series_id`, not as a rule that
-- gets expanded at read time. Every existing read path -- the calendar range
-- query, the two conflict queries, the status updates -- already works against
-- `bookings`, so materialising the occurrences leaves all of them untouched.
-- `booking_series` exists only to answer "what else belongs with this one?".

CREATE TYPE recurrence_frequency AS ENUM ('WEEKLY', 'FORTNIGHTLY', 'MONTHLY');

CREATE TABLE booking_series (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE RESTRICT,
    staff_id UUID REFERENCES users(id) ON DELETE SET NULL,  -- NULL = whole business

    -- The rule the owner chose. MONTHLY means "same weekday of the month"
    -- (2nd Tuesday), not "same date", so an occurrence can never drift onto a
    -- weekday the business is closed.
    frequency recurrence_frequency NOT NULL,
    occurrence_count INT NOT NULL,

    -- The slot the owner actually picked. Kept so the series can be described
    -- without loading its bookings, and so the weekday/ordinal can be re-derived.
    first_start_datetime TIMESTAMP WITH TIME ZONE NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT valid_occurrence_count CHECK (occurrence_count BETWEEN 2 AND 52)
);

CREATE INDEX idx_booking_series_business_id ON booking_series(business_id);
CREATE INDEX idx_booking_series_customer_id ON booking_series(customer_id);

CREATE TRIGGER update_booking_series_updated_at BEFORE UPDATE ON booking_series
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ON DELETE SET NULL, never CASCADE: removing a series must not delete real
-- appointments that have already happened. It only unlinks them.
ALTER TABLE bookings ADD COLUMN series_id UUID REFERENCES booking_series(id) ON DELETE SET NULL;

CREATE INDEX idx_bookings_series_id ON bookings(series_id);
