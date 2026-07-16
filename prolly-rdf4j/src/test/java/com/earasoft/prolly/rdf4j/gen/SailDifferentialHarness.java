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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

/**
 * Phase 0 Step 3 of {@code prolly-rdf4j-test-strategy.md} — the <b>differential harness</b>
 * skeleton (the spine of the S-2 oracle, D-1). It replays an {@link OpStreamGen.Op} stream against
 * a {@link ProllySail} {@code SailRepository} and an RDF4J {@link MemoryStore} {@code
 * SailRepository} <b>in lockstep</b>, then exposes order-agnostic comparators: the statement set
 * (`getStatements`), `size`, `getContextIDs`, and SPARQL `SELECT` binding multisets.
 *
 * <p>Statements/bindings are compared by a <b>normalized key</b> (term kind + lexical +
 * datatype/lang + context) rather than cross-implementation {@code equals}, so a comparison
 * mismatch reflects a real divergence, not an {@code equals} quirk between two Sail value models.
 *
 * <p>This is the rig only — the assertions live in the Phase-1 properties (Steps 5–7).
 * Transactions: ADD/REMOVE/CLEAR auto-begin on both connections; COMMIT/ROLLBACK end the txn on
 * both; {@link #close()} commits any open txn.
 */
public final class SailDifferentialHarness implements AutoCloseable {

    private final Repository prolly;
    private final Repository memory;
    private final RepositoryConnection pc;
    private final RepositoryConnection mc;
    private boolean inTxn = false;

    public SailDifferentialHarness(Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        this.prolly = new SailRepository(sail);
        this.memory = new SailRepository(new MemoryStore());
        this.prolly.init();
        this.memory.init();
        this.pc = prolly.getConnection();
        this.mc = memory.getConnection();
    }

    // ---- replay -----------------------------------------------------------

    public void applyAll(List<Op> ops) {
        for (Op op : ops) apply(op);
        if (inTxn) {
            pc.commit();
            mc.commit();
            inTxn = false;
        }
    }

    public void apply(Op op) {
        switch (op.kind()) {
            case ADD -> {
                begin();
                pc.add(op.statement());
                mc.add(op.statement());
            }
            case REMOVE -> {
                begin();
                pc.remove(op.statement());
                mc.remove(op.statement());
            }
            case CLEAR -> {
                begin();
                if (op.context() == null) {
                    pc.clear();
                    mc.clear();
                } else {
                    pc.clear(op.context());
                    mc.clear(op.context());
                }
            }
            case COMMIT -> {
                if (inTxn) {
                    pc.commit();
                    mc.commit();
                    inTxn = false;
                }
            }
            case ROLLBACK -> {
                if (inTxn) {
                    pc.rollback();
                    mc.rollback();
                    inTxn = false;
                }
            }
        }
    }

    private void begin() {
        if (!inTxn) {
            // Default isolation, NOT IsolationLevels.NONE: under NONE rollback is
            // not guaranteed to undo, and MemoryStore (writes immediate) and
            // ProllySail (honors rollback) legitimately diverge — which would be a
            // spurious oracle failure. The NONE-vs-default rollback difference is a
            // real isolation-level fact, pinned separately under S-4 (Step 14).
            pc.begin();
            mc.begin();
            inTxn = true;
        }
    }

    // ---- comparators (order-agnostic) -------------------------------------

    /** The full statement set of each Sail, as normalized keys. */
    public boolean statementsAgree() {
        return statementKeys(pc).equals(statementKeys(mc));
    }

    public boolean sizeAgrees() {
        return pc.size() == mc.size();
    }

    public boolean contextsAgree() {
        return contextKeys(pc).equals(contextKeys(mc));
    }

    /** SPARQL SELECT binding multisets equal (catches cardinality divergence). */
    public boolean bindingsAgree(String sparql) {
        return bindingMultiset(pc, sparql).equals(bindingMultiset(mc, sparql));
    }

    public long prollySize() {
        return pc.size();
    }

    /**
     * Commit any open transaction on both Sails (lets a property check the final state after a
     * stream that didn't end on a COMMIT).
     */
    public void flush() {
        if (inTxn) {
            pc.commit();
            mc.commit();
            inTxn = false;
        }
    }

    /**
     * All 16 {@code getStatements(s,p,o,ctx)} wildcard masks over a sample of the current
     * statements agree across both Sails — exercises every index/scan path, and the {@code
     * {null}}=default-graph filtering (the context-leak class), not just the full {@code (*,*,*)}
     * set.
     */
    public boolean patternsAgree() {
        List<Statement> all = new ArrayList<>();
        try (var it = pc.getStatements(null, null, null, false)) {
            while (it.hasNext()) all.add(it.next());
        }
        all.sort((a, b) -> key(a).compareTo(key(b)));
        int n = Math.min(8, all.size());
        for (int i = 0; i < n; i++) {
            Statement st = all.get(i);
            for (int mask = 0; mask < 16; mask++) {
                Resource s = (mask & 1) != 0 ? st.getSubject() : null;
                IRI p = (mask & 2) != 0 ? st.getPredicate() : null;
                Value o = (mask & 4) != 0 ? st.getObject() : null;
                boolean useCtx = (mask & 8) != 0;
                if (!patternKeys(pc, s, p, o, useCtx, st.getContext())
                        .equals(patternKeys(mc, s, p, o, useCtx, st.getContext()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<String> patternKeys(
            RepositoryConnection c, Resource s, IRI p, Value o, boolean useCtx, Resource ctx) {
        Set<String> out = new HashSet<>();
        try (var it =
                useCtx ? c.getStatements(s, p, o, false, ctx) : c.getStatements(s, p, o, false)) {
            while (it.hasNext()) out.add(key(it.next()));
        }
        return out;
    }

    // ---- normalization ----------------------------------------------------

    private static Set<String> statementKeys(RepositoryConnection c) {
        Set<String> out = new HashSet<>();
        try (var it = c.getStatements(null, null, null, false)) {
            while (it.hasNext()) out.add(key(it.next()));
        }
        return out;
    }

    private static Set<String> contextKeys(RepositoryConnection c) {
        Set<String> out = new HashSet<>();
        try (var it = c.getContextIDs()) {
            while (it.hasNext()) out.add(term(it.next()));
        }
        return out;
    }

    /** Binding multiset: a sorted count map of normalized rows. */
    private static TreeMap<String, Integer> bindingMultiset(RepositoryConnection c, String sparql) {
        TreeMap<String, Integer> counts = new TreeMap<>();
        try (TupleQueryResult r = c.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            List<String> vars = new ArrayList<>(r.getBindingNames());
            while (r.hasNext()) {
                BindingSet b = r.next();
                StringBuilder sb = new StringBuilder();
                for (String v : vars)
                    sb.append(v)
                            .append('=')
                            .append(b.hasBinding(v) ? term(b.getValue(v)) : "∅")
                            .append('');
                counts.merge(sb.toString(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String key(Statement s) {
        return term(s.getSubject())
                + ''
                + term(s.getPredicate())
                + ''
                + term(s.getObject())
                + ''
                + (s.getContext() == null ? "∅" : term(s.getContext()));
    }

    /**
     * Kind-tagged normalized term: IRI / bnode-by-id / literal-with-dt+lang / RDF-star triple
     * (recursive).
     */
    private static String term(Value v) {
        if (v == null) return "∅";
        if (v.isIRI()) return "I:" + v.stringValue();
        if (v.isBNode()) return "B:" + ((org.eclipse.rdf4j.model.BNode) v).getID();
        if (v.isTriple()) {
            var t = (org.eclipse.rdf4j.model.Triple) v;
            return "T:("
                    + term(t.getSubject())
                    + ','
                    + term(t.getPredicate())
                    + ','
                    + term(t.getObject())
                    + ')';
        }
        var lit = (org.eclipse.rdf4j.model.Literal) v;
        return "L:"
                + lit.getLabel()
                + "^^"
                + lit.getDatatype().stringValue()
                + lit.getLanguage().map(l -> "@" + l).orElse("");
    }

    @Override
    public void close() {
        try {
            if (inTxn) {
                pc.rollback();
                mc.rollback();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            pc.close();
        } catch (RuntimeException ignored) {
        }
        try {
            mc.close();
        } catch (RuntimeException ignored) {
        }
        try {
            prolly.shutDown();
        } catch (RuntimeException ignored) {
        }
        try {
            memory.shutDown();
        } catch (RuntimeException ignored) {
        }
    }
}
