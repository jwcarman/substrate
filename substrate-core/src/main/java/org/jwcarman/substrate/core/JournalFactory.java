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
package org.jwcarman.substrate.core;

import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.spi.JournalSpi;

public class JournalFactory {

  private final JournalSpi journalSpi;
  private final CodecFactory codecFactory;

  public JournalFactory(JournalSpi journalSpi, CodecFactory codecFactory) {
    this.journalSpi = journalSpi;
    this.codecFactory = codecFactory;
  }

  public <T> Journal<T> create(String name, Class<T> type) {
    return new DefaultJournal<>(journalSpi, journalSpi.journalKey(name), codecFactory.create(type));
  }

  public <T> Journal<T> create(String name, TypeRef<T> typeRef) {
    return new DefaultJournal<>(
        journalSpi, journalSpi.journalKey(name), codecFactory.create(typeRef));
  }
}
