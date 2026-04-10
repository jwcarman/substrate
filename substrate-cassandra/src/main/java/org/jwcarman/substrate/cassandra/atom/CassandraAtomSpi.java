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
package org.jwcarman.substrate.cassandra.atom;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.jwcarman.substrate.core.atom.AbstractAtomSpi;
import org.jwcarman.substrate.core.atom.RawAtom;

public class CassandraAtomSpi extends AbstractAtomSpi {

  private static final String FIELD_KEY = "key";
  private static final String FIELD_VALUE = "value";
  private static final String FIELD_TOKEN = "token";

  private final CqlSession session;
  private final String tableName;
  private final PreparedStatement insertIfNotExists;
  private final PreparedStatement selectByKey;
  private final PreparedStatement updateIfExists;
  private final PreparedStatement deleteIfExists;

  public CassandraAtomSpi(CqlSession session, String prefix, String tableName) {
    super(prefix);
    this.session = session;
    this.tableName = tableName;

    String quotedToken = q(FIELD_TOKEN);

    this.insertIfNotExists =
        session.prepare(
            "INSERT INTO "
                + tableName
                + " ("
                + FIELD_KEY
                + ", "
                + FIELD_VALUE
                + ", "
                + quotedToken
                + ") VALUES (?, ?, ?) IF NOT EXISTS USING TTL ?");

    this.selectByKey =
        session.prepare(
            "SELECT "
                + FIELD_VALUE
                + ", "
                + quotedToken
                + " FROM "
                + tableName
                + " WHERE "
                + FIELD_KEY
                + " = ?");

    this.updateIfExists =
        session.prepare(
            "UPDATE "
                + tableName
                + " USING TTL ? SET "
                + FIELD_VALUE
                + " = ?, "
                + quotedToken
                + " = ? WHERE "
                + FIELD_KEY
                + " = ? IF EXISTS");

    this.deleteIfExists =
        session.prepare("DELETE FROM " + tableName + " WHERE " + FIELD_KEY + " = ? IF EXISTS");
  }

  public void createSchema() {
    session.execute(
        "CREATE TABLE IF NOT EXISTS "
            + tableName
            + " ("
            + FIELD_KEY
            + " text PRIMARY KEY, "
            + FIELD_VALUE
            + " blob, "
            + q(FIELD_TOKEN)
            + " text)");
  }

  @Override
  public void create(String key, byte[] value, String token, Duration ttl) {
    Row row =
        session
            .execute(insertIfNotExists.bind(key, ByteBuffer.wrap(value), token, ttlSeconds(ttl)))
            .one();
    if (row != null && !row.getBoolean("[applied]")) {
      throw new AtomAlreadyExistsException(key);
    }
  }

  @Override
  public Optional<RawAtom> read(String key) {
    Row row = session.execute(selectByKey.bind(key)).one();
    if (row == null) {
      return Optional.empty();
    }
    ByteBuffer buffer = row.getByteBuffer(FIELD_VALUE);
    if (buffer == null) {
      return Optional.empty();
    }
    return Optional.of(new RawAtom(toByteArray(buffer), row.getString(FIELD_TOKEN)));
  }

  @Override
  public boolean set(String key, byte[] value, String token, Duration ttl) {
    Row row =
        session
            .execute(updateIfExists.bind(ttlSeconds(ttl), ByteBuffer.wrap(value), token, key))
            .one();
    return row != null && row.getBoolean("[applied]");
  }

  @Override
  public boolean touch(String key, Duration ttl) {
    Row existing = session.execute(selectByKey.bind(key)).one();
    if (existing == null) {
      return false;
    }
    ByteBuffer value = existing.getByteBuffer(FIELD_VALUE);
    if (value == null) {
      return false;
    }
    String token = existing.getString(FIELD_TOKEN);
    Row result = session.execute(updateIfExists.bind(ttlSeconds(ttl), value, token, key)).one();
    return result != null && result.getBoolean("[applied]");
  }

  @Override
  public void delete(String key) {
    session.execute(deleteIfExists.bind(key));
  }

  private static int ttlSeconds(Duration ttl) {
    return Math.max((int) ttl.toSeconds(), 1);
  }

  private static byte[] toByteArray(ByteBuffer buffer) {
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  private static String q(String identifier) {
    return "\"" + identifier + "\"";
  }
}
