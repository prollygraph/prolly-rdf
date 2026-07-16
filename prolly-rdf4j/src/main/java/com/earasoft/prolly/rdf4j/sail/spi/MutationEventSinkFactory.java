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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Service-provider extension point for per-mutation observers on a {@code ProllySail}. The OSS sail
 * core is sink-agnostic; an implementation (typically the {@code prolly-rdf4j-enterprise}
 * event-log) is bound at configuration time, owns its own prolly tree, and is asked to commit +
 * restore alongside the data trees.
 *
 * <p>The factory carries the metadata the core needs without seeing the implementation: the {@code
 * RootMetaTree} key under which to store the sink's tree-root hash, and the {@link TupleDescriptor}
 * needed to deserialize that root on restart. Each transaction calls {@link #open(NodeStore,
 * BufferPool, StaticMap)} to get a fresh {@link MutationEventSink} instance bound to the current
 * committed root.
 *
 * <p>Implementations must keep this interface free of any implementation-specific types. Adding a
 * new sink is non-breaking (additive constructor on the sail).
 */
public interface MutationEventSinkFactory {

    /**
     * Name under which this sink's tree-root chunk hash is stored in {@code RootMetaTree}. Must be
     * unique across all sinks on a Sail. Convention: {@code "<purpose>-events"} (e.g., {@code
     * "provenance-events"} for the ADR-0003 event log).
     */
    String rootMetaTreeName();

    /**
     * Schema for the sink's prolly tree. Used by the core sail to deserialize the committed root on
     * init.
     */
    TupleDescriptor schema();

    /**
     * Open a new per-transaction sink bound to {@code committedRoot}. Pass {@code null} when the
     * sail has no prior root for this sink (fresh store, or this sink wasn't enabled at last
     * commit).
     */
    MutationEventSink open(NodeStore store, BufferPool pool, @Nullable StaticMap committedRoot);
}
