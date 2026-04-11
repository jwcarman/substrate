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
 * The Mailbox primitive — a distributed single-delivery future with TTL-based expiration. Contains
 * the {@link org.jwcarman.substrate.mailbox.Mailbox} interface, {@link
 * org.jwcarman.substrate.mailbox.MailboxFactory} for construction, and mailbox-specific exceptions
 * ({@link org.jwcarman.substrate.mailbox.MailboxExpiredException}, {@link
 * org.jwcarman.substrate.mailbox.MailboxFullException}).
 *
 * <p>Use Mailbox when you need a request-response pattern across distributed nodes — one side
 * creates the mailbox and waits, the other side delivers the single response. See {@link
 * org.jwcarman.substrate.mailbox.Mailbox} for the full contract and usage examples.
 */
package org.jwcarman.substrate.mailbox;
