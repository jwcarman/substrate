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
package org.jwcarman.substrate.core.subscription;

import org.jwcarman.substrate.NextResult;

/**
 * Base class for all {@link NextHandoff} implementations. Provides the four terminal-mark methods
 * ({@code error}, {@code markCompleted}, {@code markExpired}, {@code markDeleted}) that delegate to
 * a single {@link #mark(NextResult)} hook, eliminating ~16 lines of duplication per concrete class.
 *
 * @param <T> the type of values transferred through the handoff
 */
public abstract class AbstractHandoff<T> implements NextHandoff<T> {

  @Override
  public final void error(Throwable cause) {
    mark(new NextResult.Errored<>(cause));
  }

  @Override
  public final void markCompleted() {
    mark(new NextResult.Completed<>());
  }

  @Override
  public final void markExpired() {
    mark(new NextResult.Expired<>());
  }

  @Override
  public final void markDeleted() {
    mark(new NextResult.Deleted<>());
  }

  /**
   * Transitions the handoff to a terminal state. Implementations must be idempotent: a second call
   * must be a no-op.
   */
  protected abstract void mark(NextResult<T> terminal);
}
