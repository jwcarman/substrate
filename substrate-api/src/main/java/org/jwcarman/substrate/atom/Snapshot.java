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
package org.jwcarman.substrate.atom;

/**
 * A point-in-time view of an {@link Atom}'s value, paired with an opaque staleness token.
 *
 * <p>The {@link #token()} is an opaque marker identifying the write that produced this value. Every
 * write generates a fresh token, so a token identifies a write rather than a value: setting the
 * same value twice yields two different tokens. Tokens should be compared only via {@link
 * Object#equals(Object)}; their internal format is an implementation detail.
 *
 * <p>Pass a {@code Snapshot} to {@link Atom#subscribe(Snapshot)} to receive notifications only for
 * changes after the state it represents, or to {@link Atom#compareAndSet} to make a write
 * conditional on nothing having changed since.
 *
 * @param value the atom's value at the time this snapshot was taken
 * @param token an opaque staleness token associated with this snapshot
 * @param <T> the type of value held by the atom
 * @see Atom
 */
public record Snapshot<T>(T value, String token) {}
