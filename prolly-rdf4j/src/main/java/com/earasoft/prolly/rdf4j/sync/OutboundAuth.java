/*
 * Copyright 2026 Earasoft
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
package com.earasoft.prolly.rdf4j.sync;

/**
 * Result type from {@link SecretResolver#resolve}. Sealed so outbound-request builders can
 * exhaustively switch over the auth variants without a default arm.
 *
 * <p>Step 3 of {@code plans/admin-remotes-page.md}. Three variants for v1 — no SSH-key auth in this
 * cut (would need private-key material + HTTP Signatures sender; deferred).
 */
public sealed interface OutboundAuth
        permits OutboundAuth.NoAuth, OutboundAuth.BasicAuth, OutboundAuth.BearerAuth {

    /** No authentication — public-anonymous target. */
    record NoAuth() implements OutboundAuth {
        public static final NoAuth INSTANCE = new NoAuth();
    }

    /** HTTP Basic credentials (Authorization: Basic ...). */
    record BasicAuth(String username, String password) implements OutboundAuth {}

    /** Bearer token (Authorization: Bearer ...) — typically a PAT. */
    record BearerAuth(String tokenValue) implements OutboundAuth {}
}
