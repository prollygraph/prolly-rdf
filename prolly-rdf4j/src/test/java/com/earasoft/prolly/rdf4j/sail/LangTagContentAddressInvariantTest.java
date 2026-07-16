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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Executable form of the content-addressing identity invariant documented in {@code
 * spec-compliance/semantics/term-identity.md} ({@code IDENT-1}):
 *
 * <blockquote>
 *
 * Two equal RDF terms must share one {@code TermId} / one storage key / one root hash.
 *
 * </blockquote>
 *
 * <p>The motivating equality is language-tag case: RDF 1.1 declares {@code "x"@en-US} and {@code
 * "x"@en-us} the <em>same</em> term ({@code CANON-LANG-1}). This test proves the downstream
 * consequence the W3C SPARQL suites are structurally blind to — two independent graphs, one built
 * from each casing, produce <b>byte-identical data roots</b> (dictionary + all four quad indexes).
 * Identical roots ⇒ one {@code TermId}, one index key, one content address.
 *
 * <p><b>Why this instrument and not a statement count.</b> The obvious test — insert both casings,
 * assert one statement — is fooled: {@code Statement.equals} inherits the case-insensitive {@code
 * Value.equals}, so any {@code Set<Statement>} collapses the two regardless of how many {@code
 * TermId}s the store minted underneath. That confound is the same one that blinds the W3C result
 * oracle. Comparing <em>content-address bytes</em> (the data-root hashes, a pure function of the
 * encoded tree) sidesteps it entirely: the hash cannot be case-insensitive, so a regression that
 * reintroduces two {@code TermId}s would make the roots differ and fail this test.
 *
 * <p><b>The control arm is load-bearing.</b> A root-equality assertion that can never fail is
 * worthless, so {@link #control_genuinely_different_terms_yield_different_roots()} proves the
 * instrument enters the failing regime: a genuinely different literal ({@code "x"@fr}) produces a
 * <em>different</em> root. Equality of the canonical pair is only evidence because difference of a
 * non-canonical pair is detectable by the same measurement.
 */
class LangTagContentAddressInvariantTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * The five data-bearing roots that define "the same RDF dataset" (per
     * ProllySail.isDataTreeNoOp).
     */
    private static final String[] DATA_ROOTS = {
        RootMetaTree.NAME_DICT,
        RootMetaTree.NAME_SPOC,
        RootMetaTree.NAME_POSC,
        RootMetaTree.NAME_OSPC,
        RootMetaTree.NAME_CSPO,
    };

    /** A fresh in-memory sail holding exactly the one triple {@code <s> <p> obj}. */
    private static ProllySail sailWithOneObject(Literal obj) {
        ProllySail sail = new ProllySail();
        sail.init();
        IRI s = VF.createIRI("http://example/s");
        IRI p = VF.createIRI("http://example/p");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, obj);
            conn.commit();
        }
        return sail;
    }

    /** The content hash of one data-bearing tree in the sail's committed RootMetaTree. */
    private static byte[] dataRoot(ProllySail sail, String name) {
        return RootMetaTree.readFrom(sail.store(), sail.currentCommitHash())
                .orElseThrow(() -> new AssertionError("no RootMetaTree for the committed hash"))
                .hashOf(name)
                .orElse(null);
    }

    @Test
    void case_variant_language_tags_produce_identical_content_addresses() {
        ProllySail upper = sailWithOneObject(VF.createLiteral("hello", "en-US"));
        ProllySail lower = sailWithOneObject(VF.createLiteral("hello", "en-us"));
        try {
            assertNotNull(upper.currentCommitHash(), "the insert must have committed");
            // Every data-bearing root (dict + 4 quad orders) is byte-identical across the
            // two independent stores. The dict root identical ⇒ one TermId; the four index
            // roots identical ⇒ one storage key in every ordering. This is the invariant.
            for (String name : DATA_ROOTS) {
                assertArrayEquals(
                        dataRoot(lower, name),
                        dataRoot(upper, name),
                        "data root '" + name + "' must match for case-variant language tags");
            }
        } finally {
            upper.shutDown();
            lower.shutDown();
        }
    }

    @Test
    void control_genuinely_different_terms_yield_different_roots() {
        ProllySail enUs = sailWithOneObject(VF.createLiteral("hello", "en-us"));
        ProllySail fr = sailWithOneObject(VF.createLiteral("hello", "fr"));
        try {
            // Proves the measurement can fail: a different language tag is a different term,
            // so its dictionary + index roots differ. Without this arm, the positive test
            // above could pass on a broken (e.g. constant-returning) root accessor.
            assertFalse(
                    Arrays.equals(
                            dataRoot(enUs, RootMetaTree.NAME_DICT),
                            dataRoot(fr, RootMetaTree.NAME_DICT)),
                    "different language tags are different terms → different dictionary root");
            assertFalse(
                    Arrays.equals(
                            dataRoot(enUs, RootMetaTree.NAME_SPOC),
                            dataRoot(fr, RootMetaTree.NAME_SPOC)),
                    "different terms → different SPOC index root");
        } finally {
            enUs.shutDown();
            fr.shutDown();
        }
    }
}
