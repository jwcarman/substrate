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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.substrate.atom.AtomAlreadyExistsException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CassandraAtomSpiTest {

  @Mock private CqlSession session;
  @Mock private PreparedStatement preparedStatement;
  @Mock private BoundStatement boundStatement;
  @Mock private ResultSet resultSet;
  @Mock private Row row;

  private CassandraAtomSpi atom;

  @BeforeEach
  void setUp() {
    when(session.prepare(anyString())).thenReturn(preparedStatement);
    when(preparedStatement.bind(any(Object[].class))).thenReturn(boundStatement);
    when(session.execute(any(BoundStatement.class))).thenReturn(resultSet);

    atom = new CassandraAtomSpi(session, "substrate:atom:", "substrate_atoms");
  }

  @Test
  void preparesStatementsDuringConstruction() {
    verify(session, atLeast(4)).prepare(anyString());
  }

  @Test
  void createExecutesPreparedStatement() {
    when(resultSet.one()).thenReturn(row);
    when(row.getBoolean("[applied]")).thenReturn(true);

    atom.create("key1", "value".getBytes(StandardCharsets.UTF_8), "token1", Duration.ofMinutes(5));

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void createThrowsWhenAlreadyExists() {
    when(resultSet.one()).thenReturn(row);
    when(row.getBoolean("[applied]")).thenReturn(false);

    byte[] value = "value".getBytes(StandardCharsets.UTF_8);
    Duration ttl = Duration.ofMinutes(5);
    assertThatThrownBy(() -> atom.create("key1", value, "token1", ttl))
        .isInstanceOf(AtomAlreadyExistsException.class);
  }

  @Test
  void readReturnsEmptyWhenRowIsNull() {
    when(resultSet.one()).thenReturn(null);

    assertThat(atom.read("key1")).isEmpty();
  }

  @Test
  void readReturnsAtomWhenRowExists() {
    when(resultSet.one()).thenReturn(row);
    when(row.getByteBuffer("value"))
        .thenReturn(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
    when(row.getString("token")).thenReturn("tok1");

    var result = atom.read("key1");

    assertThat(result).isPresent();
    assertThat(new String(result.get().value(), StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(result.get().token()).isEqualTo("tok1");
  }

  @Test
  void setExecutesPreparedStatement() {
    when(resultSet.one()).thenReturn(row);
    when(row.getBoolean("[applied]")).thenReturn(true);

    boolean applied =
        atom.set("key1", "value".getBytes(StandardCharsets.UTF_8), "token2", Duration.ofMinutes(5));

    assertThat(applied).isTrue();
    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void deleteExecutesPreparedStatement() {
    atom.delete("key1");

    verify(session, atLeast(1)).execute(any(BoundStatement.class));
  }

  @Test
  void atomKeyUsesConfiguredPrefix() {
    assertThat(atom.atomKey("my-atom")).isEqualTo("substrate:atom:my-atom");
  }

  @Test
  void sweepReturnsZero() {
    assertThat(atom.sweep(100)).isZero();
  }

  @Test
  void createSchemaExecutesCql() {
    when(session.execute(anyString())).thenReturn(resultSet);

    atom.createSchema();

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(session).execute(captor.capture());
    assertThat(captor.getValue()).contains("CREATE TABLE IF NOT EXISTS substrate_atoms");
    assertThat(captor.getValue()).contains("text PRIMARY KEY");
    assertThat(captor.getValue()).contains("blob");
  }
}
