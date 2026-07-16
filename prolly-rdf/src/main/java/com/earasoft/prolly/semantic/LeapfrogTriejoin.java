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

import com.dolthub.prolly.Cursor;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.indexing.LeapfrogJoin;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.jspecify.annotations.Nullable;

/**
 * The hierarchical leapfrog triejoin driver (Phases 1–2 of {@code
 * multi-variable-leapfrog-triejoin.md}). Given a list of {@link QuadPattern}s and a global variable
 * order, it binds variables depth-first: at each variable it leapfrog-joins the
 * <i>participating</i> relations' current trie level (reusing the single-variable {@link
 * LeapfrogJoin}); for each value it descends every participant ({@code seek}+{@code open}),
 * recurses, then backtracks ({@code up}).
 *
 * <p><b>Each pattern is presented as a variable-only trie built in the global variable order</b>
 * (D-4 Phase-A): its variable columns are projected — in ascending global-order — out of the SPOC
 * index, with constants pre-filtered, into a small materialized index. This makes the driver
 * uniform (no constant-seeking, no SPOC column-order constraint) and — crucially — <b>handles
 * cyclic queries</b> (the triangle has no SPOC-consistent variable order, so the SPOC-trie approach
 * could not run it; projecting each pattern in the chosen order can).
 *
 * <p>Delivers Gain&nbsp;1 (multi-variable binding) and Gain&nbsp;2 (no O(N²) intermediate — output
 * is emitted depth-first; each projected trie is O(matches) ≤ O(N), never a cross-product). The
 * projected tries are materialized (v1), so the <i>space</i> win holds but the <i>time</i> win
 * (O(N^1.5), sublinear seek) awaits Phase&nbsp;2 Step&nbsp;9 / Phase&nbsp;3 real permutation
 * indexes.
 *
 * @apiNote Built from a list of {@link QuadPattern}s plus a global variable order; binds the
 *     variables depth-first and yields complete bindings. Unlike the single-variable star join, it
 *     handles cyclic patterns (the triangle) because each pattern is projected into a variable-only
 *     trie in the chosen order rather than seeking the shared index in one fixed column order.
 * @implNote <b>Collaborators:</b> {@link LeapfrogJoin} (the single-variable intersection reused at
 *     each variable level), {@link QuadPattern} (the patterns being joined), {@link
 *     ProjectingIterator} / {@link SortedProjection} (project each pattern into a variable-only
 *     trie in global order), {@link TrieIterator} (the open/seek/up trie cursor over a projected
 *     relation), {@link Dictionary} + {@link TermEncoder} (encode constants and decode bound values
 *     back to terms), and a {@link DirectBufferPool} for scratch tuples. <b>Dependents:</b> {@link
 *     GraphPatternEngine}'s multi-variable evaluation path, and through it the cyclic-pattern query
 *     route.
 */
public final class LeapfrogTriejoin {

    // Index column layouts: layout[logical s=0/p=1/o=2/c=3] = physical key column.
    private static final int[] SPOC_LAYOUT = {0, 1, 2, 3}; // key = (s,p,o,c)
    private static final int[] POSC_LAYOUT = {2, 0, 1, 3}; // key = (p,o,s,c)

    private final List<String> varOrder;
    private final DirectBufferPool pool;
    private final StaticMap spoc;
    // @Nullable: p-major index for seek-scoping p-bound patterns; null when no POSC index is
    // available, in which case p-bound patterns full-scan SPOC (projectScoped guards posc != null).
    private final @Nullable StaticMap posc;
    private final TupleDescriptor spocDesc;
    private final TupleDescriptor
            joinDesc; // single-column, derived from the index's s/p/o column type
    // (IRI for the raw engine, Int64 once TermId-keyed)
    private final List<Plan> plans = new ArrayList<>();
    private final List<List<Integer>> participants; // global var idx -> plan indices
    private boolean unsatisfiable = false; // an all-constant pattern had no match
    private long projScanRows = 0; // rows examined building projections (Option B evidence)
    private long materializedRows = 0; // rows kept across all projections (Gain-2 space metric)

    /**
     * When non-null, the index is TermId-keyed and pattern constants are encoded to TermIds via
     * this shared dictionary (ADR-0036); when null, the legacy raw-IRI path (constants as UTF-8) is
     * used. {@code encodeConstant} guards {@code dict != null}.
     */
    private final @Nullable Dictionary dict;

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final class Plan {
        final TrieIterator trie;
        final int[] levelVar; // trie level -> global variable index (ascending)

        Plan(TrieIterator trie, int[] levelVar) {
            this.trie = trie;
            this.levelVar = levelVar;
        }
    }

    /**
     * {@code posc} is the p-major index used to seek-scope p-bound patterns (ADR-0034 Option B). It
     * may be {@code null} when no POSC index is available, in which case p-bound patterns full-scan
     * SPOC (correct, just not scoped).
     */
    public LeapfrogTriejoin(
            List<QuadPattern> patterns,
            List<String> varOrder,
            StaticMap spoc,
            @Nullable StaticMap posc,
            TupleDescriptor spocDesc,
            DirectBufferPool pool) {
        this(patterns, varOrder, spoc, posc, spocDesc, pool, null);
    }

    /**
     * TermId-native ctor (ADR-0036): {@code spoc}/{@code posc} are TermId-keyed and {@code dict}
     * encodes pattern constants to TermIds.
     */
    public LeapfrogTriejoin(
            List<QuadPattern> patterns,
            List<String> varOrder,
            StaticMap spoc,
            @Nullable StaticMap posc,
            TupleDescriptor spocDesc,
            DirectBufferPool pool,
            @Nullable Dictionary dict) {
        this.varOrder = varOrder;
        this.pool = pool;
        this.spoc = spoc;
        this.posc = posc;
        this.spocDesc = spocDesc;
        this.dict = dict;
        // The join key is one variable column; s/p/o share a type in any SPOC-shaped
        // index, so the subject column's type represents them (IRI today, Int64 once
        // the index is TermId-keyed — ADR-0036).
        this.joinDesc =
                new TupleDescriptor(List.of(new Type(spocDesc.typeAt(0).encoding(), false)));
        this.participants = new ArrayList<>();
        for (int i = 0; i < varOrder.size(); i++) participants.add(new ArrayList<>());

        for (QuadPattern q : patterns) {
            String[] vals = {q.s().value(), q.p().value(), q.o().value()};
            boolean[] isVar = {q.s().isVar(), q.p().isVar(), q.o().isVar()};
            byte[][] constCol = new byte[3][]; // s/p/o constant bytes, null if variable
            List<int[]> vc =
                    new ArrayList<>(); // [spocColumn, globalVarIdx] for each variable column
            boolean missingConstant = false;
            for (int col = 0; col < 3; col++) {
                if (isVar[col]) {
                    int gi = varOrder.indexOf(vals[col]);
                    if (gi < 0)
                        throw new IllegalArgumentException(
                                "variable not in varOrder: " + vals[col]);
                    vc.add(new int[] {col, gi});
                } else {
                    constCol[col] = encodeConstant(vals[col]);
                    if (constCol[col] == null) missingConstant = true; // absent from the store
                }
            }
            // Default graph: a null context is the reserved sentinel TermId.ZERO (value 0) —
            // ProllySail
            // stores default-graph quads with c = TermId.ZERO — which has no IRI in the dictionary,
            // so
            // bypass encodeConstant and seek the zero context directly. Non-null c is a named-graph
            // IRI.
            byte[] graph = (q.c() == null) ? le8(0L) : encodeConstant(q.c());
            // A constant (object or graph) absent from the dictionary matches no row → this pattern
            // (and so the whole conjunctive BGP) is empty. Folding the graph-null check into the
            // same guard also narrows graph to non-null for the projectScoped / existsMatch calls
            // below (encodeConstant is now @Nullable — null = term absent from the dictionary).
            if (graph == null || missingConstant) {
                unsatisfiable = true;
                continue;
            }
            vc.sort((a, b) -> Integer.compare(a[1], b[1])); // trie levels in ascending global order

            if (vc.isEmpty()) { // all-constant pattern: a pure existence filter
                if (!existsMatch(spoc, constCol, graph)) unsatisfiable = true;
                continue;
            }

            // Build the variable-only trie: project the variable columns (in global
            // order) out of the rows matching this pattern's constants. Per
            // ADR-0034 Option B, scope the scan by seeking the leading-constant
            // prefix on whichever maintained index puts those constants first —
            // SPOC for a bound subject, POSC for a bound predicate — instead of
            // scanning the whole store.
            int k = vc.size();
            List<Type> projTypes = new ArrayList<>();
            for (int i = 0; i < k; i++)
                projTypes.add(new Type(spocDesc.typeAt(0).encoding(), false));
            TupleDescriptor projDesc = new TupleDescriptor(projTypes);
            StaticMap proj = projectScoped(constCol, graph, vc, k, projDesc);

            int[] levelVar = new int[k];
            for (int i = 0; i < k; i++) levelVar[i] = vc.get(i)[1];
            int planIdx = plans.size();
            plans.add(new Plan(new TrieIterator(proj, projDesc, pool), levelVar));
            for (int gi : levelVar) participants.get(gi).add(planIdx);
        }
    }

    /**
     * All variable bindings satisfying the BGP, as a list of var→value maps (a drain of {@link
     * #cursor()}).
     */
    public List<Map<String, byte[]>> solve() {
        List<Map<String, byte[]>> out = new ArrayList<>();
        BindingCursor c = cursor();
        while (c.next()) out.add(c.toRow());
        return out;
    }

    /**
     * A pull cursor over the leapfrog descent (Phase 1 of {@code triejoin-streaming-results.md}):
     * {@link BindingCursor#next()} advances to the next complete binding, mutating a reused
     * var-indexed buffer in place — no per-row {@code Map} until the consumer asks for one. {@code
     * solve()} is now a thin drain over it (back-compat). The explicit-stack state machine mirrors
     * the former recursion <i>exactly</i> (same {@code levelIterator}/{@code seek}/{@code
     * open}/{@code up} sequence in the same order), so the join work — and {@link #seekWork()} — is
     * unchanged; only emission differs.
     *
     * <p>(Named {@code BindingCursor}, not {@code Cursor}, to avoid colliding with the imported
     * prolly-tree {@link com.dolthub.prolly.Cursor} used by {@code projectScoped}.)
     */
    public BindingCursor cursor() {
        return new BindingCursor();
    }

    /** Leapfrog join for variable level {@code vi} over its participants' current trie level. */
    private MapIterator enterLevel(int vi) {
        List<Integer> parts = participants.get(vi);
        if (parts.isEmpty()) {
            throw new IllegalStateException(
                    "variable " + varOrder.get(vi) + " appears in no pattern");
        }
        List<MapIterator> iters = new ArrayList<>();
        for (int pi : parts) iters.add(plans.get(pi).trie.levelIterator());
        return new LeapfrogJoin(iters, joinDesc);
    }

    /** Descend every participant of level {@code vi} to the bound value (seek + open). */
    private void descendLevel(int vi, byte[] val) {
        for (int pi : participants.get(vi)) {
            plans.get(pi).trie.seek(val);
            plans.get(pi).trie.open();
        }
    }

    /**
     * Ascend every participant of level {@code vi} — the former recursion's post-recurse {@code
     * up}.
     */
    private void ascendLevel(int vi) {
        for (int pi : participants.get(vi)) plans.get(pi).trie.up();
    }

    /**
     * Little-endian {@code long} from the first 8 bytes — pure array reads + shifts, <b>no {@code
     * MemorySegment.ofArray} wrapper and no VarHandle resolution</b>. The former {@code
     * MemorySegment.ofArray(b).get(JAVA_LONG_UNALIGNED.withOrder(LITTLE_ENDIAN), 0)} form built a
     * <em>non-constant</em> layout per call (the {@code withOrder} anti-pattern {@code
     * Tuple.LE_U16} warns about), forcing a per-call VarHandle cache lookup ({@code
     * computeIfAbsent} + layout {@code hashCode}) — a CPU flame of the flag-ON triangle attributed
     * ~14% of evaluation to that resolution chain, called per {@code termId} per field per row.
     * Bit-ops compile to near-optimal code (the JIT folds them toward a single unaligned load).
     * Requires {@code b.length >= 8} (the Int64 TermId path).
     */
    private static long readInt64Le(byte[] b) {
        return (b[0] & 0xFFL)
                | (b[1] & 0xFFL) << 8
                | (b[2] & 0xFFL) << 16
                | (b[3] & 0xFFL) << 24
                | (b[4] & 0xFFL) << 32
                | (b[5] & 0xFFL) << 40
                | (b[6] & 0xFFL) << 48
                | (b[7] & 0xFFL) << 56;
    }

    /**
     * Pull cursor over complete bindings — the explicit-stack equivalent of the former recursive
     * {@code solve}. {@link #next()} positions a reused {@code bound[]} buffer at the next
     * solution; read the current binding by index via {@link #field(int)} / {@link #termId(int)}
     * <b>before</b> the next {@code next()} (the buffer is overwritten in place). Single-pass,
     * forward-only, not thread-safe.
     */
    public final class BindingCursor {
        private final int n = varOrder.size();
        private final byte[][] bound = new byte[n][];
        private final MapIterator[] levelLj = new MapIterator[n]; // per-level leapfrog join (lazy)
        private final byte[][] scratch =
                new byte[n][]; // reused per-level value buffers (D-1: no per-row getField byte[])
        private int cur = 0; // the level being advanced
        private boolean atLeaf = false; // a complete binding is currently exposed
        private boolean done = unsatisfiable;

        /** Advance to the next complete binding; {@code false} when exhausted. */
        public boolean next() {
            if (done) return false;
            if (n == 0) { // degenerate: one empty binding (parity with the former solve)
                done = true;
                return true;
            }
            if (atLeaf) { // resume past the binding just exposed: ascend the deepest level, then
                // advance
                atLeaf = false;
                cur = n - 1;
                ascendLevel(cur);
            }
            while (cur >= 0) {
                if (levelLj[cur] == null) levelLj[cur] = enterLevel(cur);
                if (levelLj[cur].next()) {
                    bound[cur] = readField0(levelLj[cur].key(), cur);
                    descendLevel(cur, bound[cur]);
                    if (cur == n - 1) { // a full binding is ready
                        atLeaf = true;
                        return true;
                    }
                    cur++;
                } else {
                    levelLj[cur] = null; // reset so a later re-entry rebuilds the level iterators
                    cur--;
                    if (cur >= 0) ascendLevel(cur); // the parent's post-descent up()
                }
            }
            done = true;
            return false;
        }

        /** The current binding's value for variable index {@code i} (raw index-column bytes). */
        public byte[] field(int i) {
            return bound[i];
        }

        /**
         * The current binding's value for variable {@code i} as a TermId (the Int64-keyed path).
         */
        public long termId(int i) {
            return readInt64Le(bound[i]);
        }

        /** Materialize the current binding as a var→value map (the {@code solve()} drain shape). */
        Map<String, byte[]> toRow() {
            Map<String, byte[]> row = new LinkedHashMap<>();
            // COPY: toRow RETAINS rows (solve() materializes a List of these), but bound[] now
            // points
            // at the reused per-level scratch buffers (D-1) — without the clone every row would
            // alias
            // the last row's values. This is the read-before-next contract (D-2) made concrete: the
            // materialize path pays the copy; the streaming Sail path does not (it reads + resolves
            // each row before advancing).
            for (int i = 0; i < n; i++) {
                byte[] v = bound[i];
                row.put(varOrder.get(i), v == null ? null : v.clone());
            }
            return row;
        }

        /**
         * Stop the cursor early (e.g. a {@code LIMIT} short-circuit); the shared tries need no
         * release.
         */
        public void close() {
            done = true;
        }

        /**
         * Field 0 of the level's join key, copied into the reused per-level {@code scratch} buffer
         * — the D-1 allocation fix. Replaces {@code new Tuple(key).getField(0)}, eliminating the
         * per-row {@code getField} {@code byte[]} (71M sampled) and the {@code getFieldSegment}
         * {@code HeapMemorySegment} slice (62M) — the descent's ~52% residual (Step 1). {@code
         * fieldRange} reads the field offsets with no allocation; {@code MemorySegment.copy} fills
         * the reused buffer (re-sized only when the field width changes — Int64 TermId keys are a
         * constant 8, so one allocation total). Returns {@code null} for an absent / NULL-encoded
         * field — exact parity with the former {@code getField(0)}. The buffer is reused across
         * rows, so read it before the next {@code next()} (D-2); the only retaining consumer,
         * {@code toRow()}, clones.
         */
        private byte @Nullable [] readField0(MemorySegment key, int level) {
            long range = new Tuple(key).fieldRange(0);
            if (range == Tuple.FIELD_ABSENT) return null;
            int start = (int) (range >>> 32);
            int end = (int) (range & 0xFFFFFFFFL);
            if (start == end) return null; // NULL-encoded field — parity with getField(0)
            int len = end - start;
            byte[] buf = scratch[level];
            if (buf == null || buf.length != len) buf = scratch[level] = new byte[len];
            MemorySegment.copy(key, ValueLayout.JAVA_BYTE, start, buf, 0, len);
            return buf;
        }
    }

    /**
     * Total sublinear-seek work ({@code atKey}/skip count) across every participant trie — the
     * leapfrog level scans plus the descent seeks. The WCOJ work-bound evidence ({@code
     * TriejoinWorkBoundProperty}) fits this against the input size to show sub-quadratic growth on
     * an instance where a binary-join plan is quadratic.
     */
    public long seekWork() {
        long total = 0;
        for (Plan p : plans) total += p.trie.seekCount();
        return total;
    }

    /**
     * Rows examined while building projections — the seek-scoped scan visits only the matching
     * prefix range, so this is far below the store size for selective patterns (ADR-0034 Option B
     * evidence).
     */
    public long projScanRows() {
        return projScanRows;
    }

    /**
     * Total rows held across all projected tries — the triejoin's peak materialized intermediate
     * (Gain-2 space metric). Linear in N (each pattern's projection is ≤ its match count), never
     * the O(N²) cross-product a binary plan's first join would materialize.
     */
    public long materializedRows() {
        return materializedRows;
    }

    /**
     * Project a pattern's variable columns (global order) into a fresh sorted map, scanning only
     * the leading-constant prefix range of a chosen index.
     */
    private StaticMap projectScoped(
            byte[][] constCol, byte[] graph, List<int[]> vc, int k, TupleDescriptor projDesc) {
        // Choose the index whose physical column order puts this pattern's bound
        // columns first: SPOC (s-major) for a bound subject, POSC (p-major) for a
        // bound predicate. Otherwise fall back to a full SPOC scan.
        StaticMap scanIndex;
        int[] layout;
        if (constCol[0] != null) { // subject bound
            scanIndex = spoc;
            layout = SPOC_LAYOUT;
        } else if (constCol[1] != null && posc != null) { // predicate bound
            scanIndex = posc;
            layout = POSC_LAYOUT;
        } else { // no usable leading prefix
            scanIndex = spoc;
            layout = SPOC_LAYOUT;
        }
        byte[][] allConst = {constCol[0], constCol[1], constCol[2], graph}; // graph always constant

        // The seek prefix = the maximal run of leading constant columns in this
        // index's physical order.
        List<byte[]> seekPrefix = new ArrayList<>();
        for (int phys = 0; phys < 4; phys++) {
            byte[] cv = allConst[physToLogical(layout, phys)];
            if (cv == null) break;
            seekPrefix.add(cv);
        }

        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, projDesc), store, projDesc, pool);
        if (scanIndex.root() != null) {
            Cursor cur;
            if (seekPrefix.isEmpty()) {
                cur = Cursor.atStart(scanIndex.store(), scanIndex.root());
            } else {
                TupleBuilder pb = new TupleBuilder(pool);
                for (int i = 0; i < seekPrefix.size(); i++) pb.putField(i, seekPrefix.get(i));
                cur =
                        Cursor.atKey(
                                scanIndex.store(),
                                scanIndex.root(),
                                pb.build().segment(),
                                spocDesc);
            }
            while (cur.isValid()) {
                Tuple row = new Tuple(cur.currentKey());
                if (!physPrefixMatches(row, seekPrefix)) break; // past the prefix range
                projScanRows++;
                if (layoutConstMatch(row, allConst, layout)) { // bound object + graph
                    TupleBuilder tb = new TupleBuilder(pool);
                    for (int i = 0; i < k; i++) tb.putField(i, row.getField(layout[vc.get(i)[0]]));
                    mm.put(tb.build().segment(), MemorySegment.NULL);
                    materializedRows++;
                }
                cur.advance();
            }
        }
        return mm.flush();
    }

    private static int physToLogical(int[] layout, int phys) {
        for (int logical = 0; logical < 4; logical++) if (layout[logical] == phys) return logical;
        throw new IllegalStateException("bad layout");
    }

    private static boolean physPrefixMatches(Tuple row, List<byte[]> seekPrefix) {
        for (int i = 0; i < seekPrefix.size(); i++) {
            byte[] f = row.getField(i);
            if (f == null || !Arrays.equals(f, seekPrefix.get(i))) return false;
        }
        return true;
    }

    private static boolean layoutConstMatch(Tuple row, byte[][] allConst, int[] layout) {
        for (int logical = 0; logical < 4; logical++) {
            if (allConst[logical] != null) {
                byte[] f = row.getField(layout[logical]);
                if (f == null || !Arrays.equals(f, allConst[logical])) return false;
            }
        }
        return true;
    }

    /**
     * Encode a constant IRI term to its index-column bytes: in the legacy raw-IRI path (no
     * dictionary) the UTF-8 bytes; in the TermId path the 8-byte little-endian {@code TermId}, or
     * {@code null} if the term is absent from the dictionary (a constant the store never saw — the
     * pattern matches nothing).
     */
    private byte @Nullable [] encodeConstant(String iriValue) {
        if (dict == null) return iriValue.getBytes(StandardCharsets.UTF_8);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createIRI(iriValue), a);
            return dict.findTermId(enc).map(t -> le8(t.value())).orElse(null);
        }
    }

    /**
     * A long as 8 little-endian bytes — matching the {@code Int64} column layout {@code
     * TypeCodec.readInt64} compares (see {@code TrieIterator.successor}).
     */
    private static byte[] le8(long x) {
        byte[] b = new byte[8];
        MemorySegment.ofArray(b)
                .set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, x);
        return b;
    }

    private static boolean constMatch(Tuple row, byte[][] constCol, byte[] graph) {
        for (int col = 0; col < 3; col++) {
            if (constCol[col] != null) {
                byte[] f = row.getField(col);
                if (f == null || !Arrays.equals(f, constCol[col])) return false;
            }
        }
        byte[] g = row.getField(3);
        return g != null && Arrays.equals(g, graph);
    }

    private static boolean existsMatch(StaticMap spoc, byte[][] constCol, byte[] graph) {
        MapIterator it = spoc.iter();
        while (it.next()) if (constMatch(new Tuple(it.key()), constCol, graph)) return true;
        return false;
    }
}
