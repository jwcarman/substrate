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
package org.jwcarman.substrate.nats;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.impl.ErrorListenerLoggerImpl;
import io.nats.client.support.Status;

/**
 * {@link io.nats.client.ErrorListener} that suppresses the 404 "No Messages" pull status that
 * substrate-nats' journal reads receive at the end of every {@code pullNoWait} request. All other
 * error/warning callbacks are forwarded to {@link ErrorListenerLoggerImpl}.
 *
 * <p>Wire it in when constructing the NATS {@link Connection}:
 *
 * <pre>{@code
 * Options options = Options.builder()
 *     .server("nats://localhost:4222")
 *     .errorListener(new SilencePullStatusWarnings())
 *     .build();
 * Connection connection = Nats.connect(options);
 * }</pre>
 */
public class SilencePullStatusWarnings extends ErrorListenerLoggerImpl {

  private static final int NO_MESSAGES = 404;

  @Override
  public void pullStatusWarning(Connection conn, JetStreamSubscription sub, Status status) {
    if (status != null && status.getCode() == NO_MESSAGES) {
      return;
    }
    super.pullStatusWarning(conn, sub, status);
  }
}
