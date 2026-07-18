-- =====================================================
-- CUSTOMER ACCOUNTS
-- Customers can register a login that is not tied to any
-- business. Business-scoped users keep business_id.
-- =====================================================

-- Guarded because long-lived dev databases predate the native enum type
-- (role is varchar there); on migration-built databases the type exists.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
        ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'CUSTOMER';
    END IF;
END $$;

ALTER TABLE users ALTER COLUMN business_id DROP NOT NULL;

-- The (business_id, email) unique constraint treats NULLs as distinct,
-- so account-level (business-less) emails need their own uniqueness.
CREATE UNIQUE INDEX idx_users_account_email ON users(email) WHERE business_id IS NULL;
