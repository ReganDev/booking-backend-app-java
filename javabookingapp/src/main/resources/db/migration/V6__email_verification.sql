-- Grandfather existing accounts before verification becomes mandatory.
UPDATE users SET email_verified = TRUE WHERE email_verified IS DISTINCT FROM TRUE;

-- Abort with a useful diagnostic before normalizing or adding global uniqueness.
DO $$
DECLARE
    duplicate_emails TEXT;
BEGIN
    SELECT string_agg(normalized_email, ', ' ORDER BY normalized_email)
      INTO duplicate_emails
      FROM (
          SELECT lower(btrim(email)) AS normalized_email
          FROM users
          GROUP BY lower(btrim(email))
          HAVING count(*) > 1
      ) duplicates;

    IF duplicate_emails IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot apply V6 email uniqueness: case-insensitive duplicate user emails exist: %',
            duplicate_emails;
    END IF;
END $$;

UPDATE users SET email = lower(btrim(email));

-- Replace legacy scoped uniqueness with one account per normalized email.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_business_id_email_key;
DROP INDEX IF EXISTS idx_users_account_email;
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    email_snapshot VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT email_verification_token_lifecycle CHECK (
        consumed_at IS NULL OR revoked_at IS NULL
    )
);

CREATE INDEX idx_email_verification_tokens_user_id
    ON email_verification_tokens(user_id);
CREATE INDEX idx_email_verification_tokens_expires_at
    ON email_verification_tokens(expires_at);
CREATE INDEX idx_email_verification_tokens_active_user
    ON email_verification_tokens(user_id, created_at DESC)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
