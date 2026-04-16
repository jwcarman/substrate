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
package org.jwcarman.substrate.core.notifier;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers Jackson binding hints for {@link RawNotification} so substrate's notifier wire envelope
 * survives a GraalVM native-image build.
 *
 * <p>{@link DefaultNotifier} serializes {@code RawNotification} via {@code
 * codecFactory.create(RawNotification.class)} at runtime, which Spring AOT cannot detect from
 * static bean definitions. Without these hints, every notification publish/receive would fail in a
 * native image because Jackson cannot reflect on the record's components.
 *
 * <p>{@link BindingReflectionHintsRegistrar} walks record components transitively, so registering
 * {@code RawNotification} also covers its nested {@link PrimitiveType} and {@link EventType} enums.
 *
 * <p>Consumer payload types stored via atom/journal/mailbox codecs are the consumer's
 * responsibility — substrate registers hints only for its own internal wire types.
 */
public class SubstrateRuntimeHints implements RuntimeHintsRegistrar {

  private static final BindingReflectionHintsRegistrar BINDING =
      new BindingReflectionHintsRegistrar();

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    BINDING.registerReflectionHints(hints.reflection(), RawNotification.class);
  }
}
