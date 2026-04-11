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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConfiguredSubscriberTest {

  @Test
  void onNextDispatchesToHandler() {
    var captured = new AtomicReference<String>();
    var sub = new ConfiguredSubscriber<>(captured::set, null, null, null, null, null);
    sub.onNext("value");
    assertThat(captured.get()).isEqualTo("value");
  }

  @Test
  void onNextWithNullHandlerIsNoOp() {
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, null);
    assertThatNoException().isThrownBy(() -> sub.onNext("value"));
  }

  @Test
  void onCompletedDispatchesToHandler() {
    var called = new AtomicBoolean();
    var sub =
        new ConfiguredSubscriber<String>(null, () -> called.set(true), null, null, null, null);
    sub.onCompleted();
    assertThat(called.get()).isTrue();
  }

  @Test
  void onCompletedWithNullHandlerIsNoOp() {
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, null);
    assertThatNoException().isThrownBy(sub::onCompleted);
  }

  @Test
  void onExpiredDispatchesToHandler() {
    var called = new AtomicBoolean();
    var sub =
        new ConfiguredSubscriber<String>(null, null, () -> called.set(true), null, null, null);
    sub.onExpired();
    assertThat(called.get()).isTrue();
  }

  @Test
  void onExpiredWithNullHandlerIsNoOp() {
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, null);
    assertThatNoException().isThrownBy(sub::onExpired);
  }

  @Test
  void onDeletedDispatchesToHandler() {
    var called = new AtomicBoolean();
    var sub =
        new ConfiguredSubscriber<String>(null, null, null, () -> called.set(true), null, null);
    sub.onDeleted();
    assertThat(called.get()).isTrue();
  }

  @Test
  void onDeletedWithNullHandlerIsNoOp() {
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, null);
    assertThatNoException().isThrownBy(sub::onDeleted);
  }

  @Test
  void onCancelledDispatchesToHandler() {
    var called = new AtomicBoolean();
    var sub =
        new ConfiguredSubscriber<String>(null, null, null, null, () -> called.set(true), null);
    sub.onCancelled();
    assertThat(called.get()).isTrue();
  }

  @Test
  void onCancelledWithNullHandlerIsNoOp() {
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, null);
    assertThatNoException().isThrownBy(sub::onCancelled);
  }

  @Test
  void onErrorDispatchesToHandler() {
    var captured = new AtomicReference<Throwable>();
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, captured::set);
    var cause = new RuntimeException("boom");
    sub.onError(cause);
    assertThat(captured.get()).isSameAs(cause);
  }

  @Test
  void onErrorWithNullHandlerIsNoOp() {
    var sub = new ConfiguredSubscriber<String>(null, null, null, null, null, null);
    assertThatNoException().isThrownBy(() -> sub.onError(new RuntimeException("boom")));
  }
}
