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

/**
 * Substrate subscription model — the consumer-side types shared across all primitives. Contains
 * {@link org.jwcarman.substrate.Subscription} and its two concrete forms ({@link
 * org.jwcarman.substrate.BlockingSubscription} and {@link
 * org.jwcarman.substrate.CallbackSubscription}), the sealed {@link
 * org.jwcarman.substrate.NextResult} outcome type, and {@link
 * org.jwcarman.substrate.CallbackSubscriberBuilder} for configuring callback-style subscriptions.
 */
package org.jwcarman.substrate;
