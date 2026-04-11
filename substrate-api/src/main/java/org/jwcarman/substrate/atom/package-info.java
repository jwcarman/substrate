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
 * The Atom primitive — a distributed, leased, keyed reference with change notification. Contains
 * the {@link org.jwcarman.substrate.atom.Atom} interface, {@link
 * org.jwcarman.substrate.atom.AtomFactory} for construction, {@link
 * org.jwcarman.substrate.atom.Snapshot} for point-in-time views, and atom-specific exceptions.
 *
 * <p>Use Atom when you need a shared variable that multiple processes can read and write, with
 * notification when it changes. See {@link org.jwcarman.substrate.atom.Atom} for the full contract
 * and usage examples.
 */
package org.jwcarman.substrate.atom;
