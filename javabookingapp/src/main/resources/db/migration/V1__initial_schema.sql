-- =====================================================
-- Booking System Database Schema
-- Multi-tenant SaaS booking application
-- =====================================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =====================================================
-- ENUM TYPES
-- =====================================================

CREATE TYPE user_role AS ENUM ('OWNER', 'STAFF', 'ADMIN');
CREATE TYPE booking_status AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED', 'NO_SHOW');
CREATE TYPE day_of_week AS ENUM ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY');

-- =====================================================
-- BUSINESSES (Tenants)
-- =====================================================

CREATE TABLE businesses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,  -- URL-friendly identifier (e.g., "joes-barber-shop")
    description TEXT,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50),

    -- Address
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city VARCHAR(100),
    state VARCHAR(100),
    postal_code VARCHAR(20),
    country VARCHAR(100) DEFAULT 'Belfast',

    -- Settings
    timezone VARCHAR(50) NOT NULL DEFAULT 'United Kingdom/London',
    currency VARCHAR(3) NOT NULL DEFAULT 'GBP',
    logo_url VARCHAR(500),

    -- Booking settings
    booking_advance_days INT DEFAULT 30,           -- How far in advance can customers book
    booking_notice_hours INT DEFAULT 24,           -- Minimum notice required for bookings
    cancellation_notice_hours INT DEFAULT 24,      -- Minimum notice for cancellations
    slot_duration_minutes INT DEFAULT 30,          -- Default slot duration
    buffer_minutes INT DEFAULT 0,                  -- Buffer between appointments

    -- Status
    is_active BOOLEAN DEFAULT TRUE,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_businesses_slug ON businesses(slug);
CREATE INDEX idx_businesses_is_active ON businesses(is_active);

-- =====================================================
-- USERS (Business owners and staff)
-- =====================================================

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,

    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,

    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(50),
    avatar_url VARCHAR(500),

    role user_role NOT NULL DEFAULT 'STAFF',

    -- For staff: can they accept bookings?
    accepts_bookings BOOLEAN DEFAULT TRUE,

    -- Status
    is_active BOOLEAN DEFAULT TRUE,
    email_verified BOOLEAN DEFAULT FALSE,

    -- Timestamps
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(business_id, email)
);

CREATE INDEX idx_users_business_id ON users(business_id);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);

-- =====================================================
-- SERVICES (What the business offers)
-- =====================================================

CREATE TABLE services (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,

    name VARCHAR(255) NOT NULL,
    description TEXT,

    duration_minutes INT NOT NULL,              -- How long the service takes
    price DECIMAL(10, 2),                       -- Price (nullable for "price on request")

    -- Display
    color VARCHAR(7) DEFAULT '#3B82F6',         -- Hex color for calendar display
    display_order INT DEFAULT 0,                -- Order in service list

    -- Status
    is_active BOOLEAN DEFAULT TRUE,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_services_business_id ON services(business_id);
CREATE INDEX idx_services_is_active ON services(is_active);

-- =====================================================
-- STAFF_SERVICES (Which staff can perform which services)
-- =====================================================

CREATE TABLE staff_services (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,

    -- Optional: staff-specific duration/price overrides
    custom_duration_minutes INT,
    custom_price DECIMAL(10, 2),

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(user_id, service_id)
);

CREATE INDEX idx_staff_services_user_id ON staff_services(user_id);
CREATE INDEX idx_staff_services_service_id ON staff_services(service_id);

-- =====================================================
-- SCHEDULES (Regular working hours)
-- =====================================================

CREATE TABLE schedules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,  -- NULL = business-wide schedule

    day_of_week day_of_week NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,

    is_active BOOLEAN DEFAULT TRUE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Prevent duplicate schedules for same day
    UNIQUE(business_id, user_id, day_of_week),

    -- Ensure end time is after start time
    CONSTRAINT valid_time_range CHECK (end_time > start_time)
);

CREATE INDEX idx_schedules_business_id ON schedules(business_id);
CREATE INDEX idx_schedules_user_id ON schedules(user_id);
CREATE INDEX idx_schedules_day_of_week ON schedules(day_of_week);

-- =====================================================
-- SCHEDULE_BREAKS (Breaks within working hours)
-- =====================================================

CREATE TABLE schedule_breaks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    schedule_id UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,

    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    label VARCHAR(100),  -- e.g., "Lunch break"

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT valid_break_range CHECK (end_time > start_time)
);

CREATE INDEX idx_schedule_breaks_schedule_id ON schedule_breaks(schedule_id);

-- =====================================================
-- BLOCKED_TIMES (Holidays, time off, one-time blocks)
-- =====================================================

CREATE TABLE blocked_times (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,  -- NULL = business-wide block

    start_datetime TIMESTAMP WITH TIME ZONE NOT NULL,
    end_datetime TIMESTAMP WITH TIME ZONE NOT NULL,

    reason VARCHAR(255),  -- e.g., "Public Holiday", "Annual Leave"
    is_all_day BOOLEAN DEFAULT FALSE,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT valid_blocked_range CHECK (end_datetime > start_datetime)
);

CREATE INDEX idx_blocked_times_business_id ON blocked_times(business_id);
CREATE INDEX idx_blocked_times_user_id ON blocked_times(user_id);
CREATE INDEX idx_blocked_times_dates ON blocked_times(start_datetime, end_datetime);

-- =====================================================
-- CUSTOMERS (People who book appointments)
-- =====================================================

CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,

    email VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    phone VARCHAR(50),

    notes TEXT,  -- Internal notes about the customer

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(business_id, email)
);

CREATE INDEX idx_customers_business_id ON customers(business_id);
CREATE INDEX idx_customers_email ON customers(email);
CREATE INDEX idx_customers_name ON customers(last_name, first_name);

-- =====================================================
-- BOOKINGS (Appointments)
-- =====================================================

CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    business_id UUID NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE RESTRICT,
    staff_id UUID REFERENCES users(id) ON DELETE SET NULL,  -- Assigned staff member

    -- Timing
    start_datetime TIMESTAMP WITH TIME ZONE NOT NULL,
    end_datetime TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Status
    status booking_status NOT NULL DEFAULT 'PENDING',

    -- Pricing at time of booking (snapshot)
    price DECIMAL(10, 2),

    -- Notes
    customer_notes TEXT,    -- Notes from customer when booking
    internal_notes TEXT,    -- Internal staff notes

    -- Cancellation
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason TEXT,

    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT valid_booking_range CHECK (end_datetime > start_datetime)
);

CREATE INDEX idx_bookings_business_id ON bookings(business_id);
CREATE INDEX idx_bookings_customer_id ON bookings(customer_id);
CREATE INDEX idx_bookings_service_id ON bookings(service_id);
CREATE INDEX idx_bookings_staff_id ON bookings(staff_id);
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_dates ON bookings(start_datetime, end_datetime);
CREATE INDEX idx_bookings_business_dates ON bookings(business_id, start_datetime);

-- =====================================================
-- REFRESH_TOKENS (For JWT authentication)
-- =====================================================

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    token_hash VARCHAR(255) NOT NULL UNIQUE,

    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,

    -- Device info for multi-device support
    device_info VARCHAR(255),
    ip_address VARCHAR(45),

    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);

-- =====================================================
-- TRIGGER: Auto-update updated_at timestamp
-- =====================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_businesses_updated_at BEFORE UPDATE ON businesses
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_services_updated_at BEFORE UPDATE ON services
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_schedules_updated_at BEFORE UPDATE ON schedules
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_customers_updated_at BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_bookings_updated_at BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
