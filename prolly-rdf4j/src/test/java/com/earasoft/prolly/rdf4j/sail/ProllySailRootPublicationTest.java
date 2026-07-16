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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Pins the root-publication invariant behind the fix for the lock-free fork race ({@code
 * prolly-rdf4j/plans/prollysail-root-publication-race.md}): the Sail keeps its four core roots as
 * ONE atomically-published immutable {@link Snapshot}, republished after every core-root mutation,
 * and a connection forks from that single reference (one read) — so a connection opened
 * concurrently with a commit can't fork a torn mix of two commits' roots.
 *
 * <p>This pins the <b>mechanism</b> (the snapshot is present, tracks commits, and is immutable so
 * it can be shared across forks). The by-construction race-freedom <i>under concurrency</i> is the
 * jcstress step (plan Step 4); a single-threaded test cannot prove it.
 */
class ProllySailRootPublicationTest {

    private static IRI iri(ValueFactory vf, String s) {
        return vf.createIRI("urn:test:" + s);
    }

    @Test
    void published_snapshot_is_present_and_empty_after_init() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            Snapshot snap = sail.publishedSnapshot();
            assertNotNull(snap, "a fork needs a non-null snapshot even before any commit");
            assertNull(snap.dictRoot(), "no commits yet -> empty dict root");
            assertNull(snap.indexRoots().get(QuadOrder.SPOC), "no commits yet -> empty SPOC root");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void published_snapshot_advances_after_a_commit() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            assertNull(sail.publishedSnapshot().dictRoot(), "empty before the commit");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "s"), iri(vf, "p"), iri(vf, "o"));
                conn.commit();
            }
            Snapshot after = sail.publishedSnapshot();
            assertNotNull(after.dictRoot(), "commit must re-publish: dict root now set");
            assertNotNull(
                    after.indexRoots().get(QuadOrder.SPOC),
                    "commit must re-publish: SPOC index root now set");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void published_snapshot_index_map_is_unmodifiable() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            Snapshot snap = sail.publishedSnapshot();
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> snap.indexRoots().put(QuadOrder.SPOC, null),
                    "the published snapshot must be immutable so it can be shared across forks");
        } finally {
            sail.shutDown();
        }
    }
}
