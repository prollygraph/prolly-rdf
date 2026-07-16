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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 0 Step 3 of {@code multi-variable-leapfrog-triejoin.md} — single-pattern enumeration via
 * the {@link TrieIterator}. A {@link QuadPattern} navigates a SPOC index as a trie: a
 * <b>constant</b> column is sought-to (and only descends if present), a <b>variable</b> column is
 * enumerated. The set of variable bindings the trie produces must equal the oracle filter over the
 * quad set.
 *
 * <p>Crucially this covers the <b>gapped</b> shape {@code (?s, p_const, ?o)} — a variable, a
 * constant, then a variable — the exact shape that broke the old single-variable star engine; the
 * trie navigates the constant in the middle correctly because it descends column by column.
 */
class SinglePatternTrieProperty {

    private static final List<String> SO = List.of("e0", "e1", "e2");
    private static final List<String> P = List.of("p0", "p1");
    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    record Quad(String s, String p, String o) {}

    /** A pattern: each of s,p,o is null (a variable) or a bound constant; c == "g". */
    record Pat(String s, String p, String o) {}

    @Provide
    Arbitrary<Set<Quad>> quads() {
        return Combinators.combine(Arbitraries.of(SO), Arbitraries.of(P), Arbitraries.of(SO))
                .as(Quad::new)
                .set()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    /** s,o each a constant-or-var; p a constant-or-var. (null == variable.) */
    @Provide
    Arbitrary<Pat> patterns() {
        Arbitrary<String> sv = Arbitraries.of("e0", "e1", "e2", null);
        Arbitrary<String> pv = Arbitraries.of("p0", "p1", null);
        Arbitrary<String> ov = Arbitraries.of("e0", "e1", "e2", null);
        return Combinators.combine(sv, pv, ov).as(Pat::new);
    }

    @Property(tries = 100)
    void trieEnumeratesAPatternsBindings(
            @ForAll @From("quads") Set<Quad> quads, @ForAll @From("patterns") Pat pat) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
            for (Quad q : quads) mm.put(spoc(pool, q.s(), q.p(), q.o(), G), MemorySegment.NULL);
            StaticMap map = mm.flush();

            // pattern columns 0..3; null => variable, else the constant; c is fixed "g".
            String[] cols = {pat.s(), pat.p(), pat.o(), G};

            // Oracle: quads matching the constants, projected onto the variable columns.
            Set<List<String>> oracle = new HashSet<>();
            for (Quad q : quads) {
                String[] qc = {q.s(), q.p(), q.o(), G};
                boolean match = true;
                for (int i = 0; i < 4; i++)
                    if (cols[i] != null && !cols[i].equals(qc[i])) match = false;
                if (match) oracle.add(projectVars(cols, qc));
            }

            // Trie enumeration.
            Set<List<String>> got = new HashSet<>();
            TrieIterator trie = new TrieIterator(map, SPOC, pool);
            String[] cur = new String[4];
            dfs(trie, 0, cols, cur, got);

            assertEquals(
                    oracle,
                    got,
                    "trie pattern-enumeration must equal the oracle filter; pattern=" + pat);
        }
    }

    private static void dfs(
            TrieIterator t, int col, String[] cols, String[] cur, Set<List<String>> out) {
        if (col == 4) {
            out.add(projectVars(cols, cur));
            return;
        }
        boolean leaf = (col == 3);
        if (cols[col] != null) { // constant: seek, descend only if present
            t.seek(cols[col].getBytes(StandardCharsets.UTF_8));
            if (t.atEnd() || !Arrays.equals(t.key(), cols[col].getBytes(StandardCharsets.UTF_8)))
                return;
            cur[col] = cols[col];
            if (leaf) dfs(t, col + 1, cols, cur, out);
            else {
                t.open();
                dfs(t, col + 1, cols, cur, out);
                t.up();
            }
        } else { // variable: enumerate
            while (!t.atEnd()) {
                cur[col] = new String(t.key(), StandardCharsets.UTF_8);
                if (leaf) dfs(t, col + 1, cols, cur, out);
                else {
                    t.open();
                    dfs(t, col + 1, cols, cur, out);
                    t.up();
                }
                t.next();
            }
        }
    }

    /** The values at the columns that are variables in the pattern (column order). */
    private static List<String> projectVars(String[] cols, String[] vals) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (cols[i] == null) out.add(vals[i]);
        return out;
    }

    private static MemorySegment spoc(
            DirectBufferPool pool, String s, String p, String o, String c) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, p.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, o.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, c.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
