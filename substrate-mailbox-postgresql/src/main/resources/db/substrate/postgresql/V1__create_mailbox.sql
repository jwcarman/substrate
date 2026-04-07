CREATE TABLE IF NOT EXISTS substrate_mailbox (
    key         VARCHAR(512) PRIMARY KEY,
    value       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
