/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.substrate.journal.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jwcarman.substrate.spi.AbstractJournal;
import org.jwcarman.substrate.spi.JournalEntry;

public class CassandraJournal extends AbstractJournal {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_ENTRY_ID = "entry_id";
  private static final String FIELD_DATA = "data";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String COMPLETED_DATA = "__COMPLETED__";

  private final CqlSession session;
  private final String tableName;
  private final Duration defaultTtl;

  private final PreparedStatement insertStatement;
  private final PreparedStatement insertWithTtlStatement;
  private final PreparedStatement readAfterStatement;
  private final PreparedStatement readLastStatement;
  private final PreparedStatement deleteStatement;

  public CassandraJournal(
      CqlSession session, String prefix, String tableName, Duration defaultTtl) {
    super(prefix);
    this.session = session;
    this.tableName = tableName;
    this.defaultTtl = defaultTtl;

    this.insertStatement =
        session.prepare(
            "INSERT INTO "
                + tableName
                + " ("
                + FIELD_KEY
                + ", "
                + FIELD_ENTRY_ID
                + ", "
                + FIELD_DATA
                + ", "
                + FIELD_TIMESTAMP
                + ") VALUES (?, ?, ?, ?)");

    this.insertWithTtlStatement =
        session.prepare(
            "INSERT INTO "
                + tableName
                + " ("
                + FIELD_KEY
                + ", "
                + FIELD_ENTRY_ID
                + ", "
                + FIELD_DATA
                + ", "
                + FIELD_TIMESTAMP
                + ") VALUES (?, ?, ?, ?) USING TTL ?");

    this.readAfterStatement =
        session.prepare(
            "SELECT "
                + FIELD_ENTRY_ID
                + ", "
                + FIELD_DATA
                + ", "
                + FIELD_TIMESTAMP
                + " FROM "
                + tableName
                + " WHERE "
                + FIELD_KEY
                + " = ? AND "
                + FIELD_ENTRY_ID
                + " > ? ORDER BY "
                + FIELD_ENTRY_ID
                + " ASC");

    this.readLastStatement =
        session.prepare(
            "SELECT "
                + FIELD_ENTRY_ID
                + ", "
                + FIELD_DATA
                + ", "
                + FIELD_TIMESTAMP
                + " FROM "
                + tableName
                + " WHERE "
                + FIELD_KEY
                + " = ? ORDER BY "
                + FIELD_ENTRY_ID
                + " DESC LIMIT ?");

    this.deleteStatement =
        session.prepare("DELETE FROM " + tableName + " WHERE " + FIELD_KEY + " = ?");
  }

  public void createSchema() {
    session.execute(
        "CREATE TABLE IF NOT EXISTS "
            + tableName
            + " ("
            + FIELD_KEY
            + " TEXT, "
            + FIELD_ENTRY_ID
            + " TIMEUUID, "
            + FIELD_DATA
            + " TEXT, "
            + FIELD_TIMESTAMP
            + " TIMESTAMP, "
            + "PRIMARY KEY ("
            + FIELD_KEY
            + ", "
            + FIELD_ENTRY_ID
            + ")"
            + ") WITH CLUSTERING ORDER BY ("
            + FIELD_ENTRY_ID
            + " ASC)");
  }

  @Override
  public String append(String key, String data) {
    return append(key, data, defaultTtl);
  }

  @Override
  public String append(String key, String data, Duration ttl) {
    UUID entryId = Uuids.timeBased();
    Instant now = Instant.now();

    Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
    if (effectiveTtl != null && !effectiveTtl.isZero()) {
      session.execute(
          insertWithTtlStatement.bind(key, entryId, data, now, (int) effectiveTtl.toSeconds()));
    } else {
      session.execute(insertStatement.bind(key, entryId, data, now));
    }

    return entryId.toString();
  }

  @Override
  public Stream<JournalEntry> readAfter(String key, String afterId) {
    UUID afterUuid = UUID.fromString(afterId);
    ResultSet rs = session.execute(readAfterStatement.bind(key, afterUuid));

    List<JournalEntry> entries = new ArrayList<>();
    for (Row row : rs) {
      if (!COMPLETED_DATA.equals(row.getString(FIELD_DATA))) {
        entries.add(mapRow(key, row));
      }
    }
    return entries.stream();
  }

  @Override
  public Stream<JournalEntry> readLast(String key, int count) {
    // Request extra to account for possible completion marker
    ResultSet rs = session.execute(readLastStatement.bind(key, count + 1));

    List<JournalEntry> entries = new ArrayList<>();
    for (Row row : rs) {
      if (!COMPLETED_DATA.equals(row.getString(FIELD_DATA))) {
        entries.add(mapRow(key, row));
      }
    }

    if (entries.size() > count) {
      entries = entries.subList(0, count);
    }
    Collections.reverse(entries);
    return entries.stream();
  }

  @Override
  public void complete(String key) {
    UUID entryId = Uuids.timeBased();
    Instant now = Instant.now();

    if (defaultTtl != null && !defaultTtl.isZero()) {
      session.execute(
          insertWithTtlStatement.bind(
              key, entryId, COMPLETED_DATA, now, (int) defaultTtl.toSeconds()));
    } else {
      session.execute(insertStatement.bind(key, entryId, COMPLETED_DATA, now));
    }
  }

  @Override
  public void delete(String key) {
    session.execute(deleteStatement.bind(key));
  }

  private JournalEntry mapRow(String key, Row row) {
    UUID entryId = row.getUuid(FIELD_ENTRY_ID);
    String data = row.getString(FIELD_DATA);
    Instant timestamp = row.getInstant(FIELD_TIMESTAMP);
    return new JournalEntry(entryId.toString(), key, data, timestamp);
  }
}
