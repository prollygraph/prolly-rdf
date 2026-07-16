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
package com.earasoft.prolly.rdf4j.gen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Phase 1 Step 5 of {@code prolly-rdf4j-test-strategy.md} — the <b>differential oracle</b> (S-2,
 * the headline capability). A generated {@link OpStreamGen.Op} stream is replayed against {@code
 * ProllySail} and an RDF4J {@code MemoryStore} in lockstep; after <b>every commit</b> (and at the
 * end) the two must agree on {@code getStatements} (all 16 wildcard masks), {@code size}, and
 * {@code getContextIDs}. jqwik shrinks any divergence to a minimal failing op sequence, which the
 * assertion message prints.
 */
class SailDifferentialProperty {

    private final List<Path> tempDirs = new ArrayList<>();

    @Property(tries = 100)
    void prollySailEqualsMemoryStore(@ForAll @From("opStreams") List<Op> ops) throws IOException {
        Path dir = Files.createTempDirectory("sail-diff-");
        tempDirs.add(dir);
        try (SailDifferentialHarness h = new SailDifferentialHarness(dir)) {
            for (Op op : ops) {
                h.apply(op);
                if (op.kind() == Op.Kind.COMMIT) assertAgree(h, ops);
            }
            h.flush();
            assertAgree(h, ops);
        }
    }

    private static void assertAgree(SailDifferentialHarness h, List<Op> ops) {
        assertTrue(
                h.statementsAgree(),
                () -> "getStatements(*,*,*) diverged for op stream:\n" + render(ops));
        assertTrue(h.sizeAgrees(), () -> "size() diverged for op stream:\n" + render(ops));
        assertTrue(
                h.contextsAgree(), () -> "getContextIDs() diverged for op stream:\n" + render(ops));
        assertTrue(
                h.patternsAgree(),
                () -> "a wildcard getStatements pattern diverged for op stream:\n" + render(ops));
    }

    private static String render(List<Op> ops) {
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
            sb.append("  ").append(op.kind());
            if (op.statement() != null) sb.append(' ').append(op.statement());
            if (op.context() != null) sb.append(" ctx=").append(op.context());
            sb.append('\n');
        }
        return sb.toString();
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
        tempDirs.clear();
    }

    // Lexically-stable op streams (IRI/BNode/plain/lang terms). Two surfaces are
    // deliberately excluded and covered elsewhere: foreign-factory RDF-star ingest
    // (a gap, RdfStarIngestGapTest) and typed-literal lexical canonicalization
    // (a fidelity question, S-3 / Step 8). Over the structural surface, ProllySail
    // must be provably equivalent to MemoryStore.
    @Provide
    Arbitrary<List<Op>> opStreams() {
        return OpStreamGen.differentialOpStreams();
    }
}
