CREATE TABLE IF NOT EXISTS substrate_journal_entries (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(512) NOT NULL,
    data        TEXT NOT NULL,
    timestamp   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_substrate_journal_entries_key_id ON substrate_journal_entries (key, id);

CREATE TABLE IF NOT EXISTS substrate_journal_completed (
    key         VARCHAR(512) PRIMARY KEY
);
