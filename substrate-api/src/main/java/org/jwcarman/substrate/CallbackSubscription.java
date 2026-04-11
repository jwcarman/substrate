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

/**
 * A push-based {@link Subscription} where values are delivered to a registered handler on a
 * background feeder thread. This is a marker interface with no additional methods beyond those
 * inherited from {@link Subscription} — all interaction with the subscription (receiving values,
 * handling errors, etc.) happens through the handlers registered via {@link
 * CallbackSubscriberBuilder} at subscription time.
 *
 * <p>Calling {@link #cancel()} stops the background feeder thread and releases associated
 * resources.
 *
 * @see CallbackSubscriberBuilder
 * @see BlockingSubscription
 */
public interface CallbackSubscription extends Subscription {}
