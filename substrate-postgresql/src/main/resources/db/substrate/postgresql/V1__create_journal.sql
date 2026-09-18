-- Journal-level lease. `dies_at` is the journal's current deadline: the
-- inactivity deadline while active (pushed forward by every append), the
-- retention deadline once completed. NULL means no lease -- never expires.
CREATE TABLE IF NOT EXISTS substrate_journal (
    key                   VARCHAR(512) PRIMARY KEY,
    inactivity_ttl_millis BIGINT      NOT NULL DEFAULT 0,
    dies_at               TIMESTAMPTZ,
    completed             BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_substrate_journal_dies_at
  ON substrate_journal (dies_at);

-- `expires_at` is the entry's own TTL deadline. NULL means no TTL.
CREATE TABLE IF NOT EXISTS substrate_journal_entries (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(512) NOT NULL,
    data        BYTEA NOT NULL,
    timestamp   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_substrate_journal_entries_key_id ON substrate_journal_entries (key, id);

CREATE INDEX IF NOT EXISTS idx_substrate_journal_entries_expires_at ON substrate_journal_entries (expires_at);
