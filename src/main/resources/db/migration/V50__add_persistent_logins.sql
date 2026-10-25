-- Migration V50: Durable sessions and Remember Me persistent logins table (#415).
-- Stores persistent tokens for durable form login and Google login sessions across restarts.

CREATE TABLE persistent_logins (
    series VARCHAR(64) PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    token VARCHAR(64) NOT NULL,
    previous_token VARCHAR(64),
    last_used TIMESTAMP WITH TIME ZONE NOT NULL,
    remember_me BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_persistent_logins_username ON persistent_logins(username);
CREATE INDEX idx_persistent_logins_expires_at ON persistent_logins(expires_at);
