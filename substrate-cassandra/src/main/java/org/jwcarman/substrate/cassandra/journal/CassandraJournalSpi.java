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
package org.jwcarman.substrate.cassandra.journal;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.jwcarman.substrate.core.journal.AbstractJournalSpi;
import org.jwcarman.substrate.core.journal.RawJournalEntry;

public class CassandraJournalSpi extends AbstractJournalSpi {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_ENTRY_ID = "entry_id";
  private static final String FIELD_DATA = "data";
  private static final String FIELD_TIMESTAMP = "timestamp";
  private static final String SELECT = "SELECT ";
  private static final String FROM = " FROM ";
  private static final String WHERE = " WHERE ";
  private static final ByteBuffer COMPLETED_DATA = ByteBuffer.wrap("__COMPLETED__".getBytes());

  private final CqlSession session;
  private final String tableName;
  private final Duration defaultTtl;

  private final PreparedStatement insertStatement;
  private final PreparedStatement insertWithTtlStatement;
  private final PreparedStatement readAfterStatement;
  private final PreparedStatement readLastStatement;
  private final PreparedStatement deleteStatement;

  public CassandraJournalSpi(
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
            SELECT
                + FIELD_ENTRY_ID
                + ", "
                + FIELD_DATA
                + ", "
                + FIELD_TIMESTAMP
                + FROM
                + tableName
                + WHERE
                + FIELD_KEY
                + " = ? AND "
                + FIELD_ENTRY_ID
                + " > ? ORDER BY "
                + FIELD_ENTRY_ID
                + " ASC");

    this.readLastStatement =
        session.prepare(
            SELECT
                + FIELD_ENTRY_ID
                + ", "
                + FIELD_DATA
                + ", "
                + FIELD_TIMESTAMP
                + FROM
                + tableName
                + WHERE
                + FIELD_KEY
                + " = ? ORDER BY "
                + FIELD_ENTRY_ID
                + " DESC LIMIT ?");

    this.deleteStatement = session.prepare("DELETE FROM " + tableName + WHERE + FIELD_KEY + " = ?");
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
            + " BLOB, "
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
  public String append(String key, byte[] data, Duration ttl) {
    UUID entryId = Uuids.timeBased();
    Instant now = Instant.now();
    ByteBuffer dataBuffer = ByteBuffer.wrap(data);

    Duration effectiveTtl = ttl != null ? ttl : defaultTtl;
    if (effectiveTtl != null && !effectiveTtl.isZero()) {
      session.execute(
          insertWithTtlStatement.bind(
              key, entryId, dataBuffer, now, (int) effectiveTtl.toSeconds()));
    } else {
      session.execute(insertStatement.bind(key, entryId, dataBuffer, now));
    }

    return entryId.toString();
  }

  @Override
  public List<RawJournalEntry> readAfter(String key, String afterId) {
    UUID afterUuid = UUID.fromString(afterId);
    ResultSet rs = session.execute(readAfterStatement.bind(key, afterUuid));

    List<RawJournalEntry> entries = new ArrayList<>();
    for (Row row : rs) {
      if (!COMPLETED_DATA.equals(row.getByteBuffer(FIELD_DATA))) {
        entries.add(mapRow(key, row));
      }
    }
    return entries;
  }

  @Override
  public List<RawJournalEntry> readLast(String key, int count) {
    // Request extra to account for possible completion marker
    ResultSet rs = session.execute(readLastStatement.bind(key, count + 1));

    List<RawJournalEntry> entries = new ArrayList<>();
    for (Row row : rs) {
      if (!COMPLETED_DATA.equals(row.getByteBuffer(FIELD_DATA))) {
        entries.add(mapRow(key, row));
      }
    }

    if (entries.size() > count) {
      entries = entries.subList(0, count);
    }
    Collections.reverse(entries);
    return entries;
  }

  @Override
  public void complete(String key, Duration retentionTtl) {
    UUID entryId = Uuids.timeBased();
    Instant now = Instant.now();
    ByteBuffer completedMarker = COMPLETED_DATA.duplicate();

    Duration effectiveTtl = retentionTtl != null ? retentionTtl : defaultTtl;
    if (effectiveTtl != null && !effectiveTtl.isZero()) {
      session.execute(
          insertWithTtlStatement.bind(
              key, entryId, completedMarker, now, (int) effectiveTtl.toSeconds()));
    } else {
      session.execute(insertStatement.bind(key, entryId, completedMarker, now));
    }
  }

  @Override
  public boolean isComplete(String key) {
    ResultSet rs =
        session.execute(SELECT + FIELD_DATA + FROM + tableName + WHERE + FIELD_KEY + " = ?", key);
    for (Row row : rs) {
      if (COMPLETED_DATA.equals(row.getByteBuffer(FIELD_DATA))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void delete(String key) {
    session.execute(deleteStatement.bind(key));
  }

  private RawJournalEntry mapRow(String key, Row row) {
    UUID entryId = row.getUuid(FIELD_ENTRY_ID);
    ByteBuffer buffer = row.getByteBuffer(FIELD_DATA);
    byte[] data = buffer != null ? toByteArray(buffer) : new byte[0];
    Instant timestamp = row.getInstant(FIELD_TIMESTAMP);
    return new RawJournalEntry(entryId.toString(), key, data, timestamp);
  }

  private static byte[] toByteArray(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }
}
