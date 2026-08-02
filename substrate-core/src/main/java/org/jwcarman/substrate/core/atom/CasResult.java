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

/**
 * Outcome of a conditional {@link AtomSpi#compareAndSet compareAndSet} write.
 *
 * <p>{@link #TOKEN_MISMATCH} and {@link #ABSENT} are kept distinct because they call for opposite
 * caller behavior: a mismatch is retryable, while an absent atom is terminal and would make a retry
 * loop spin forever.
 */
public enum CasResult {

  /** The expected token matched and the new value and TTL were written. */
  COMMITTED,

  /** A live atom exists at the key, but its token differs from the expected token. */
  TOKEN_MISMATCH,

  /** No live atom exists at the key — it has expired or been deleted. */
  ABSENT
}
