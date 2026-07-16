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

import com.dolthub.prolly.*;

/**
 *
 *
 * <h3>Iri + QuadPattern Value-Type Test</h3>
 *
 * <p>Pins the small-but-load-bearing semantics of {@link com.earasoft.prolly.semantic.Iri} and
 * {@link com.earasoft.prolly.semantic.QuadPattern}: variable detection (the {@code "?"} prefix),
 * toString convention, factory equality, and variable-name lookup over a 4-field pattern.
 *
 * <p><b>The Gap:</b> these are records with one direct test reference apiece (incidental via {@code
 * QuadStoreDemo}). They're the API the BGP engine binds against — a regression in {@code isVar} or
 * {@code findVarIdx} would silently misroute query joins.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>{@code Iri.isVar()} returns true for "?x" / "?anything", false for constants ("Bob",
 *       "&lt;Alice&gt;"), and false for null value.
 *   <li>{@code Iri.toString()} wraps non-vars in angle brackets, leaves vars verbatim.
 *   <li>Record equality and hashCode work as expected (so {@code Iri} can be used as a map key).
 *   <li>{@code QuadPattern.findVarIdx} returns 0/1/2/3 for s/p/o/c respectively when the variable
 *       name appears in that position, and -1 when it doesn't.
 *   <li>{@code QuadPattern.isVar(field)} returns true exactly when the field's value starts with
 *       "?".
 * </ol>
 */
public class IriQuadPatternTest {
    public static void main(String[] args) {
        System.out.println("--- Iri + QuadPattern Value-Type Test ---");

        // Oracle 1: Iri.isVar
        if (!Iri.of("?x").isVar()) throw new RuntimeException("Iri('?x') should be var");
        if (!Iri.of("?subject").isVar())
            throw new RuntimeException("Iri('?subject') should be var");
        if (Iri.of("Bob").isVar()) throw new RuntimeException("Iri('Bob') should not be var");
        if (Iri.of("<Alice>").isVar())
            throw new RuntimeException("Iri('<Alice>') should not be var");
        if (Iri.of(null).isVar()) throw new RuntimeException("Iri(null) should not be var");
        System.out.println("Iri.isVar() correct on var, constant, and null. (1/5)");

        // Oracle 2: toString convention.
        if (!"?x".equals(Iri.of("?x").toString())) {
            throw new RuntimeException("var Iri.toString = " + Iri.of("?x"));
        }
        if (!"<Bob>".equals(Iri.of("Bob").toString())) {
            throw new RuntimeException("constant Iri.toString = " + Iri.of("Bob"));
        }
        System.out.println("Iri.toString wraps constants in <>, preserves vars. (2/5)");

        // Oracle 3: record equality and hashCode.
        if (!Iri.of("Bob").equals(Iri.of("Bob"))) {
            throw new RuntimeException("Iri.equals should be content-based");
        }
        if (Iri.of("Bob").equals(Iri.of("Carol"))) {
            throw new RuntimeException("Iri.equals should distinguish values");
        }
        if (Iri.of("Bob").hashCode() != Iri.of("Bob").hashCode()) {
            throw new RuntimeException("Iri.hashCode should be stable on equal values");
        }
        System.out.println("Iri equality / hashCode is content-based. (3/5)");

        // Oracle 4: QuadPattern.findVarIdx returns the right slot.
        QuadPattern q = QuadPattern.of("?s", "follows", "?o", "?c");
        if (q.findVarIdx("?s") != 0) throw new RuntimeException("?s should be at idx 0");
        if (q.findVarIdx("?o") != 2) throw new RuntimeException("?o should be at idx 2");
        if (q.findVarIdx("?c") != 3) throw new RuntimeException("?c should be at idx 3");
        if (q.findVarIdx("follows") != 1)
            throw new RuntimeException("'follows' should be at idx 1");
        if (q.findVarIdx("?nope") != -1) throw new RuntimeException("missing var should be -1");
        // c can be null — make sure findVarIdx handles it without NPE.
        QuadPattern qNullC = QuadPattern.of("?s", "follows", "?o", null);
        if (qNullC.findVarIdx("?c") != -1)
            throw new RuntimeException("null c should match nothing");
        if (qNullC.findVarIdx("?s") != 0) throw new RuntimeException("?s with null c still at 0");
        System.out.println(
                "QuadPattern.findVarIdx returns correct slot, -1 on miss, null-c safe. (4/5)");

        // Oracle 5: QuadPattern.isVar(field).
        if (!q.isVar(q.s())) throw new RuntimeException("?s should be var");
        if (q.isVar(q.p())) throw new RuntimeException("'follows' should not be var");
        if (!q.isVar(q.o())) throw new RuntimeException("?o should be var");
        if (q.isVar(null)) throw new RuntimeException("null Iri should not be var");
        System.out.println("QuadPattern.isVar correct on var, constant, null. (5/5)");

        System.out.println("--- Iri + QuadPattern Value-Type Test PASSED ---");
    }
}
