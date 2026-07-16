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
package com.earasoft.prolly.rdf4j.sail.spi;

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.SpocKey;

/**
 * Per-transaction sink that observes every quad inserted or deleted on a {@code
 * ProllySailConnection}. Created by a {@link MutationEventSinkFactory} at fork time and committed
 * when the sail commits.
 *
 * <p>The {@code parentCommitHash} argument is the sail's committed head at fork time — empty array
 * for genesis. The actual commit hash produced by this transaction is not yet known when the sink
 * is called, mirroring the {@code ProvenanceIndex} convention; readers walk the commit log forward
 * to resolve to the introducing commit.
 */
public interface MutationEventSink {

    /**
     * Observe one insert. Called from {@code ProllySailConnection.insertEverywhere} after the data
     * indexes accept the insert.
     */
    void recordInsert(SpocKey key, byte[] parentCommitHash);

    /**
     * Observe one delete. Called from {@code ProllySailConnection.deleteEverywhere} after the data
     * indexes accept the delete.
     */
    void recordDelete(SpocKey key, byte[] parentCommitHash);

    /**
     * Flush pending events to the sink's prolly tree and return the new committed root. Called once
     * per transaction, after the data trees commit, before {@code persistMetaTreeIfConfigured}.
     */
    StaticMap commit();

    /**
     * Drop pending events without flushing. Called instead of {@link #commit} when the enclosing
     * data-tree commit will be a no-op — the events would otherwise orphan in the sink tree without
     * a corresponding commit-log entry. Discarding leaves the sink's committed tree unchanged.
     */
    void discard();
}
