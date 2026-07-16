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

import java.io.IOException;

/**
 * Default {@link RemoteRepositoryFactory} — accepts {@code http(s)://} URLs and returns an {@link
 * HttpRemoteRepository}. Used when no scheme-dispatching factory is configured, preserving the
 * HTTP-only behavior {@link RepoSync} shipped with before plan sync-ui.md Step 15.
 *
 * <p>Holds no resources — safe to instantiate ad-hoc.
 */
public final class HttpOnlyRemoteRepositoryFactory implements RemoteRepositoryFactory {

    @Override
    public RemoteRepository fromUrl(String url) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        // No explicit scheme check — HttpRemoteRepository's URI parser
        // surfaces a clear error on a non-http(s) URL via the JDK
        // HttpClient call site. A scheme prefix check here would be
        // redundant with what URI parsing already does, and easy to
        // skew (case sensitivity, trailing whitespace).
        return new HttpRemoteRepository(url);
    }
}
