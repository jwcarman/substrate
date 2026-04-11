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
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SubscriberTest {

  @Test
  void samLambdaTargetsOnNext() {
    var captured = new AtomicReference<String>();
    Subscriber<String> sub = captured::set;
    sub.onNext("hello");
    assertThat(captured.get()).isEqualTo("hello");
  }

  @Test
  void defaultOnCompletedDoesNotThrow() {
    Subscriber<String> sub = value -> {};
    assertThatNoException().isThrownBy(sub::onCompleted);
  }

  @Test
  void defaultOnExpiredDoesNotThrow() {
    Subscriber<String> sub = value -> {};
    assertThatNoException().isThrownBy(sub::onExpired);
  }

  @Test
  void defaultOnDeletedDoesNotThrow() {
    Subscriber<String> sub = value -> {};
    assertThatNoException().isThrownBy(sub::onDeleted);
  }

  @Test
  void defaultOnCancelledDoesNotThrow() {
    Subscriber<String> sub = value -> {};
    assertThatNoException().isThrownBy(sub::onCancelled);
  }

  @Test
  void defaultOnErrorDoesNotThrow() {
    Subscriber<String> sub = value -> {};
    assertThatNoException().isThrownBy(() -> sub.onError(new RuntimeException("boom")));
  }
}
