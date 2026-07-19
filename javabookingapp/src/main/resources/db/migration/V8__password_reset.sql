CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT password_reset_token_lifecycle CHECK (
        consumed_at IS NULL OR revoked_at IS NULL
    )
);

CREATE INDEX idx_password_reset_tokens_user_id
    ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_tokens_expires_at
    ON password_reset_tokens(expires_at);
CREATE INDEX idx_password_reset_tokens_active_user
    ON password_reset_tokens(user_id, created_at DESC)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
