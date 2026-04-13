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
package org.jwcarman.substrate.core.atom;

import java.time.Duration;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.codec.spi.CodecFactory;
import org.jwcarman.codec.spi.TypeRef;
import org.jwcarman.substrate.atom.Atom;
import org.jwcarman.substrate.atom.AtomFactory;
import org.jwcarman.substrate.core.lifecycle.ShutdownCoordinator;
import org.jwcarman.substrate.core.notifier.Notifier;
import org.jwcarman.substrate.core.transform.PayloadTransformer;

public class DefaultAtomFactory implements AtomFactory {

  private final AtomSpi atomSpi;
  private final CodecFactory codecFactory;
  private final PayloadTransformer transformer;
  private final Notifier notifier;
  private final Duration maxTtl;
  private final ShutdownCoordinator shutdownCoordinator;

  public DefaultAtomFactory(
      AtomSpi atomSpi,
      CodecFactory codecFactory,
      PayloadTransformer transformer,
      Notifier notifier,
      Duration maxTtl,
      ShutdownCoordinator shutdownCoordinator) {
    this.atomSpi = atomSpi;
    this.codecFactory = codecFactory;
    this.transformer = transformer;
    this.notifier = notifier;
    this.maxTtl = maxTtl;
    this.shutdownCoordinator = shutdownCoordinator;
  }

  @Override
  public <T> Atom<T> create(String name, Class<T> type, T initialValue, Duration ttl) {
    validateTtl(ttl);
    Codec<T> codec = codecFactory.create(type);
    String key = atomSpi.atomKey(name);
    byte[] bytes = codec.encode(initialValue);
    String token = DefaultAtom.token(bytes);
    atomSpi.create(key, transformer.encode(bytes), token, ttl);
    notifier.notifyAtomChanged(key);
    return new DefaultAtom<>(
        atomSpi, key, codec, transformer, notifier, maxTtl, shutdownCoordinator);
  }

  @Override
  public <T> Atom<T> create(String name, TypeRef<T> typeRef, T initialValue, Duration ttl) {
    validateTtl(ttl);
    Codec<T> codec = codecFactory.create(typeRef);
    String key = atomSpi.atomKey(name);
    byte[] bytes = codec.encode(initialValue);
    String token = DefaultAtom.token(bytes);
    atomSpi.create(key, transformer.encode(bytes), token, ttl);
    notifier.notifyAtomChanged(key);
    return new DefaultAtom<>(
        atomSpi, key, codec, transformer, notifier, maxTtl, shutdownCoordinator);
  }

  @Override
  public <T> Atom<T> connect(String name, Class<T> type) {
    Codec<T> codec = codecFactory.create(type);
    String key = atomSpi.atomKey(name);
    return new DefaultAtom<>(
        atomSpi, key, codec, transformer, notifier, maxTtl, shutdownCoordinator);
  }

  @Override
  public <T> Atom<T> connect(String name, TypeRef<T> typeRef) {
    Codec<T> codec = codecFactory.create(typeRef);
    String key = atomSpi.atomKey(name);
    return new DefaultAtom<>(
        atomSpi, key, codec, transformer, notifier, maxTtl, shutdownCoordinator);
  }

  private void validateTtl(Duration ttl) {
    if (ttl.compareTo(maxTtl) > 0) {
      throw new IllegalArgumentException(
          "Atom TTL " + ttl + " exceeds configured maximum " + maxTtl);
    }
  }
}
