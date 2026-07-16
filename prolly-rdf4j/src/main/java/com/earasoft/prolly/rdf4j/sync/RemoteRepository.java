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

import com.earasoft.prolly.sync.DatabasePackSync;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The transport-agnostic view of a remote ProllySail repository — the seam the sync engine ({@code
 * RepoSync}) talks to. An in-process implementation backs tests; an HTTP implementation (plan Step
 * 13) backs real networked sync. See {@code plans/distributed-sync.md} Phase 2.
 *
 * <p>The negotiation is server-driven and single-round (decision D-4): the client advertises what
 * it {@code want}s and what it already {@code have}s, and the remote computes — by a Merkle-skip
 * walk — exactly the chunk + commit delta to ship.
 */
public interface RemoteRepository {

    /** The remote's branches: branch name → head commit hash (a RootMetaTree hash). */
    Map<String, byte[]> advertiseRefs() throws IOException;

    /**
     * The data + history reachable from commit {@code want} that the requester does not already
     * have. The remote walks {@code want} and prunes every subtree whose root is reachable from
     * {@code have} (the Merkle skip).
     *
     * @param want a commit hash the remote advertised
     * @param have commit hashes the requester already holds (may be empty)
     */
    SyncPack fetchPack(byte[] want, Collection<byte[]> have) throws IOException;

    /** Store a received pack — its chunks into the chunk store, its commits into the log. */
    void receivePack(SyncPack pack) throws IOException;

    /**
     * Atomically move branch {@code branch} from {@code expected} to {@code desired}. Pass {@code
     * expected == null} to require the branch does not yet exist (a create).
     *
     * @return {@code true} if the ref was updated; {@code false} if the branch's current value did
     *     not match {@code expected} (a lost race)
     */
    boolean compareAndSetRef(String branch, byte @Nullable [] expected, byte[] desired)
            throws IOException;

    /**
     * Push a non-RDF substrate's chunk pack ({@code prolly-json-sync} Phase 3): store the pack's
     * chunks into the named substrate's store and compare-and-set its {@code branch} ref to {@code
     * newHead} in one call — the wire twin of {@code Database.receiveSyncPack}, keyed by {@code
     * ?substrate=} on the endpoint rather than a tag in the pack bytes (a {@code Database}
     * substrate's pack has no codec commit entries to tag).
     *
     * @return {@code true} if the substrate's ref advanced; {@code false} on a lost compare-and-set
     *     race (chunks stay — content-addressed, harmless)
     * @throws UnsupportedOperationException on transports without substrate support — v1 is
     *     HTTP-only; the gRPC + in-process transports are follow-on work (plan Phase 6)
     */
    default boolean pushSubstratePack(
            String substrate,
            String branch,
            byte[] newHead,
            byte @Nullable [] expectedOldHead,
            SyncPack pack)
            throws IOException {
        throw new UnsupportedOperationException(
                "substrate '"
                        + substrate
                        + "' sync is not supported by this transport (v1: HTTP only)");
    }

    /**
     * Fetch a non-RDF substrate's pack for {@code branch} ({@code prolly-json-sync} Step 7): the
     * remote resolves its own head for the branch, Merkle-prunes by {@code haveCommitHexes}, and
     * returns the pack together with that head — without integrating anything anywhere ("the pack
     * for inspection"; integration is the puller's local, fast-forward-gated step).
     *
     * @return the pack + the remote's head, or empty when the remote has no such branch on that
     *     substrate
     * @throws UnsupportedOperationException on transports without substrate support — v1 is
     *     HTTP-only; the gRPC + in-process transports are follow-on work (plan Phase 6)
     */
    default java.util.Optional<DatabasePackSync.PackAndHead> fetchSubstratePack(
            String substrate, String branch, java.util.Set<String> haveCommitHexes)
            throws IOException {
        throw new UnsupportedOperationException(
                "substrate '"
                        + substrate
                        + "' sync is not supported by this transport (v1: HTTP only)");
    }
}
