CREATE TABLE IF NOT EXISTS substrate_mailbox (
    key         VARCHAR(512) PRIMARY KEY,
    value       BYTEA,
    expires_at  TIMESTAMPTZ NOT NULL
);
