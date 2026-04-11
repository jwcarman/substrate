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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultSubscriberBuilderTest {

  @Test
  void eachSetterReturnsThis() {
    var builder = new DefaultSubscriberBuilder<String>();
    assertThat(builder.onNext(v -> {})).isSameAs(builder);
    assertThat(builder.onCompleted(() -> {})).isSameAs(builder);
    assertThat(builder.onExpired(() -> {})).isSameAs(builder);
    assertThat(builder.onDeleted(() -> {})).isSameAs(builder);
    assertThat(builder.onCancelled(() -> {})).isSameAs(builder);
    assertThat(builder.onError(e -> {})).isSameAs(builder);
  }

  @Test
  void buildReturnsConfiguredSubscriberThatDispatches() {
    var nextValue = new AtomicReference<String>();
    var completed = new AtomicBoolean();
    var expired = new AtomicBoolean();
    var deleted = new AtomicBoolean();
    var cancelled = new AtomicBoolean();
    var errorCause = new AtomicReference<Throwable>();

    var sub =
        new DefaultSubscriberBuilder<String>()
            .onNext(nextValue::set)
            .onCompleted(() -> completed.set(true))
            .onExpired(() -> expired.set(true))
            .onDeleted(() -> deleted.set(true))
            .onCancelled(() -> cancelled.set(true))
            .onError(errorCause::set)
            .build();

    assertThat(sub).isInstanceOf(ConfiguredSubscriber.class);

    sub.onNext("hello");
    assertThat(nextValue.get()).isEqualTo("hello");

    sub.onCompleted();
    assertThat(completed.get()).isTrue();

    sub.onExpired();
    assertThat(expired.get()).isTrue();

    sub.onDeleted();
    assertThat(deleted.get()).isTrue();

    sub.onCancelled();
    assertThat(cancelled.get()).isTrue();

    var err = new RuntimeException("boom");
    sub.onError(err);
    assertThat(errorCause.get()).isSameAs(err);
  }

  @Test
  void buildWithNoOnNextThrowsIllegalStateException() {
    var builder = new DefaultSubscriberBuilder<String>();
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("onNext");
  }

  @Test
  void fromReturnsSubscriberThatDispatches() {
    var nextValue = new AtomicReference<String>();
    var errorCause = new AtomicReference<Throwable>();

    var sub =
        DefaultSubscriberBuilder.<String>from(
            c -> c.onNext(nextValue::set).onError(errorCause::set));

    sub.onNext("world");
    assertThat(nextValue.get()).isEqualTo("world");

    var err = new RuntimeException("fail");
    sub.onError(err);
    assertThat(errorCause.get()).isSameAs(err);
  }

  @Test
  void fromWithNoOnNextThrowsIllegalStateException() {
    assertThatThrownBy(() -> DefaultSubscriberBuilder.<String>from(c -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("onNext");
  }
}
