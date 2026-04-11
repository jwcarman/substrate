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

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DefaultCallbackSubscriberBuilderTest {

  @Test
  void handlersAreNullByDefault() {
    DefaultCallbackSubscriberBuilder<String> builder = new DefaultCallbackSubscriberBuilder<>();
    assertThat(builder.errorHandler()).isNull();
    assertThat(builder.expirationHandler()).isNull();
    assertThat(builder.deleteHandler()).isNull();
    assertThat(builder.completeHandler()).isNull();
  }

  @Test
  void onErrorStoresHandlerAndReturnsSelf() {
    DefaultCallbackSubscriberBuilder<String> builder = new DefaultCallbackSubscriberBuilder<>();
    Consumer<Throwable> handler = err -> {};
    assertThat(builder.onError(handler)).isSameAs(builder);
    assertThat(builder.errorHandler()).isSameAs(handler);
  }

  @Test
  void onExpirationStoresHandlerAndReturnsSelf() {
    DefaultCallbackSubscriberBuilder<String> builder = new DefaultCallbackSubscriberBuilder<>();
    Runnable handler = () -> {};
    assertThat(builder.onExpiration(handler)).isSameAs(builder);
    assertThat(builder.expirationHandler()).isSameAs(handler);
  }

  @Test
  void onDeleteStoresHandlerAndReturnsSelf() {
    DefaultCallbackSubscriberBuilder<String> builder = new DefaultCallbackSubscriberBuilder<>();
    Runnable handler = () -> {};
    assertThat(builder.onDelete(handler)).isSameAs(builder);
    assertThat(builder.deleteHandler()).isSameAs(handler);
  }

  @Test
  void onCompleteStoresHandlerAndReturnsSelf() {
    DefaultCallbackSubscriberBuilder<String> builder = new DefaultCallbackSubscriberBuilder<>();
    Runnable handler = () -> {};
    assertThat(builder.onComplete(handler)).isSameAs(builder);
    assertThat(builder.completeHandler()).isSameAs(handler);
  }
}
