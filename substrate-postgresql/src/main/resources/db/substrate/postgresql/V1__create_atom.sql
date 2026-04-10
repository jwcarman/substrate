CREATE TABLE IF NOT EXISTS substrate_atom (
    key         TEXT        PRIMARY KEY,
    value       BYTEA       NOT NULL,
    token       TEXT        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_substrate_atom_expires_at
  ON substrate_atom (expires_at);
