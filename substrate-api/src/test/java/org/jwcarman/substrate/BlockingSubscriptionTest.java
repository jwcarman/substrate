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
package org.jwcarman.substrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BlockingSubscriptionTest {

  @Test
  void closeDelegatesToCancel() {
    var cancelCount = new AtomicInteger();
    BlockingSubscription<String> sub = stub(cancelCount);

    sub.close();

    assertThat(cancelCount.get()).isEqualTo(1);
  }

  @Test
  void tryWithResourcesInvokesCancelOnExit() {
    var cancelCount = new AtomicInteger();
    try (BlockingSubscription<String> sub = stub(cancelCount)) {
      assertThat(sub.isActive()).isTrue();
    }
    assertThat(cancelCount.get()).isEqualTo(1);
  }

  private static BlockingSubscription<String> stub(AtomicInteger cancelCount) {
    return new BlockingSubscription<>() {
      @Override
      public NextResult<String> next(Duration timeout) {
        return new NextResult.Timeout<>();
      }

      @Override
      public boolean isActive() {
        return true;
      }

      @Override
      public void cancel() {
        cancelCount.incrementAndGet();
      }
    };
  }
}
