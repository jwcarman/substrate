CREATE KEYSPACE IF NOT EXISTS substrate_test
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS substrate_test.substrate_atoms (
  key text PRIMARY KEY,
  value blob,
  "token" text
);

CREATE TABLE IF NOT EXISTS substrate_test.substrate_journal (
  key text,
  entry_id timeuuid,
  data blob,
  timestamp timestamp,
  PRIMARY KEY (key, entry_id)
) WITH CLUSTERING ORDER BY (entry_id ASC);
