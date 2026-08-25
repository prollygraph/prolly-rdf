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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * First direct tests for the {@link MutationEventSink} SPI (hardening round 1 — the inventory found
 * ZERO test references to the whole SPI). Pins the lifecycle contract an implementor gets to rely
 * on: every opened sink sees <b>exactly one</b> terminal call — {@code commit()} when the
 * transaction commits with data changes, {@code discard()} on a no-op commit AND on rollback — and
 * records every insert keyed by the fork-time parent commit hash.
 *
 * <p>The rollback half was a REAL defect this test caught red-first: {@code rollbackInternal}
 * re-forked the tables (opening a fresh sink) without discarding the old one, silently leaking
 * whatever the implementor buffers per transaction. Fixed in the same round: {@code forkTables} now
 * discards any live sink before opening the next, and the commit path nulls the consumed sink so
 * re-fork after commit cannot double-terminate it.
 */
class MutationEventSinkContractTest {

    /** Recording fake: the observable half of the SPI contract. */
    private static final class RecordingSink implements MutationEventSink {
        final List<SpocKey> inserts = new ArrayList<>();
        final List<byte[]> insertParents = new ArrayList<>();
        int commits;
        int discards;
        final NodeStore store;

        RecordingSink(NodeStore store) {
            this.store = store;
        }

        @Override
        public void recordInsert(SpocKey key, byte[] parentCommitHash) {
            inserts.add(key);
            insertParents.add(parentCommitHash);
        }

        @Override
        public void recordDelete(SpocKey key, byte[] parentCommitHash) {}

        @Override
        public StaticMap commit() {
            commits++;
            return new StaticMap(store, null, SpocKey.DESCRIPTOR);
        }

        @Override
        public void discard() {
            discards++;
        }
    }

    private static final class RecordingFactory implements MutationEventSinkFactory {
        final List<RecordingSink> opened = new ArrayList<>();

        @Override
        public String rootMetaTreeName() {
            return "test-event-sink";
        }

        @Override
        public TupleDescriptor schema() {
            return SpocKey.DESCRIPTOR;
        }

        @Override
        public MutationEventSink open(
                NodeStore store, BufferPool pool, @Nullable StaticMap committedRoot) {
            RecordingSink s = new RecordingSink(store);
            opened.add(s);
            return s;
        }

        int totalCommits() {
            return opened.stream().mapToInt(s -> s.commits).sum();
        }

        int totalDiscards() {
            return opened.stream().mapToInt(s -> s.discards).sum();
        }

        /** Sinks opened but never terminated — must be ZERO once their connection closed. */
        long unterminated() {
            return opened.stream().filter(x -> x.commits == 0 && x.discards == 0).count();
        }

        /** Sinks terminated more than once — must always be ZERO (commit XOR discard). */
        long doubleTerminated() {
            return opened.stream().filter(x -> x.commits + x.discards > 1).count();
        }
    }

    private RecordingFactory factory;
    private SailRepository repo;
    private IRI s, p, o;

    @BeforeEach
    void setUp() {
        factory = new RecordingFactory();
        repo =
                new SailRepository(
                        new ProllySail(
                                new InMemoryNodeStore(),
                                new HeapBufferPool(),
                                null,
                                null,
                                null,
                                false,
                                true,
                                factory));
        repo.init();
        ValueFactory vf = repo.getValueFactory();
        s = vf.createIRI("urn:s");
        p = vf.createIRI("urn:p");
        o = vf.createIRI("urn:o");
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    @Test
    void dataCommitDeliversEventsAndTerminatesTheSinkWithCommit() {
        try (SailRepositoryConnection con = repo.getConnection()) {
            con.begin();
            con.add(s, p, o);
            con.commit();
        }
        RecordingSink sink =
                factory.opened.stream()
                        .filter(x -> !x.inserts.isEmpty())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no sink saw the insert"));
        assertEquals(1, sink.inserts.size(), "one statement, one recorded insert");
        assertEquals(
                0,
                sink.insertParents.get(0).length,
                "fork-time parent of the FIRST-ever commit is genesis (empty hash)");
        assertEquals(1, sink.commits, "a data commit terminates the sink via commit()");
        assertEquals(0, sink.discards, "…and never also discards it");
    }

    @Test
    void noOpCommitDiscardsInsteadOfCommitting() {
        // Establish genesis first: the FIRST commit is real even when empty (it
        // creates the initial tree + commit-log entry), so the sink rightly
        // commits for it — pinned here rather than assumed away.
        try (SailRepositoryConnection con = repo.getConnection()) {
            con.begin();
            con.commit();
        }
        assertEquals(1, factory.totalCommits(), "the genesis commit is a REAL commit, even empty");
        int commitsBefore = factory.totalCommits();
        try (SailRepositoryConnection con = repo.getConnection()) {
            con.begin();
            con.commit(); // nothing staged, genesis exists: a genuine no-op
        }
        assertEquals(
                commitsBefore,
                factory.totalCommits(),
                "a no-op commit must not advance the sink tree");
        assertEquals(
                0,
                factory.unterminated(),
                "every sink (including the no-op transaction's) is terminated once its"
                        + " connection closed");
        assertEquals(0, factory.doubleTerminated(), "never commit AND discard the same sink");
    }

    /**
     * The red-first pin (0 of 3 sinks terminated before the fix): rollback's re-fork, and the
     * eventual connection close, must each terminate the sink they abandon with discard() — every
     * opened sink sees exactly one terminal call.
     */
    @Test
    void rollbackDiscardsTheOpenSink() {
        int commitsBefore = factory.totalCommits();
        int openedBefore = factory.opened.size();
        try (SailRepositoryConnection con = repo.getConnection()) {
            con.begin();
            con.add(s, p, o);
            con.rollback();
        }
        assertTrue(factory.opened.size() > openedBefore, "the transaction did open sinks");
        assertEquals(commitsBefore, factory.totalCommits(), "rolled-back events must never commit");
        assertEquals(
                0,
                factory.unterminated(),
                "every opened sink is terminated exactly once (commit XOR discard) — "
                        + factory.opened.size()
                        + " opened in total");
        assertEquals(0, factory.doubleTerminated(), "never commit AND discard the same sink");
    }
}
