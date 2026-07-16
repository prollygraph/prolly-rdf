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
 * The strategy {@link RepoSync} uses to turn a remote URL into a concrete {@link RemoteRepository}.
 * The default implementation ({@link HttpOnlyRemoteRepositoryFactory}) accepts only {@code
 * http(s)://} URLs; a Spring-wired {@code SchemeDispatchingRemoteRepositoryFactory} in {@code
 * prolly-rdf4j-rest} also accepts {@code grpc://} URLs and routes them to {@code
 * GrpcRemoteRepository} from the gRPC binding (plan sync-ui.md Step 15).
 *
 * <p>The interface lives in {@code prolly-rdf4j} (the engine module) but {@code prolly-rdf4j} stays
 * transport-agnostic — it only depends on {@code HttpRemoteRepository}, not the gRPC binding.
 * Engine → transport coupling stays one-way; cross- transport dispatch is the integration layer's
 * responsibility (D-12 of `plans/sync-ui.md`).
 *
 * <p>Implementations holding open resources (e.g. gRPC managed channels) should also implement
 * {@link AutoCloseable} and be driven by their host's lifecycle (Spring {@code destroyMethod}).
 */
public interface RemoteRepositoryFactory {

    /**
     * Build a {@link RemoteRepository} for {@code url}.
     *
     * @throws IllegalArgumentException if the URL's scheme is not one the implementation supports
     */
    RemoteRepository fromUrl(String url) throws IOException;

    /**
     * Auth-aware overload — Step 4 of {@code plans/admin-remotes-outbound-auth.md}. Default
     * implementation delegates to {@link #fromUrl(String)} when {@code auth} is {@link
     * OutboundAuth.NoAuth}; otherwise throws {@link UnsupportedOperationException} (concrete
     * implementations that actually inject auth — i.e. {@code
     * SchemeDispatchingRemoteRepositoryFactory} — override this default).
     */
    default RemoteRepository fromUrl(String url, OutboundAuth auth) throws IOException {
        if (auth == null || auth instanceof OutboundAuth.NoAuth) {
            return fromUrl(url);
        }
        throw new UnsupportedOperationException(
                "this RemoteRepositoryFactory does not support outbound auth — "
                        + "use SchemeDispatchingRemoteRepositoryFactory for credentialed remotes");
    }
}
