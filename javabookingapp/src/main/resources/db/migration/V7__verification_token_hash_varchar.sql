-- Hibernate maps Java String as VARCHAR. Keep the fixed 64-character limit
-- while using the JDBC type Hibernate validates for this field.
ALTER TABLE email_verification_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64);
