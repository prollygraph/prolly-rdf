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

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the {@link ProllySailConnection} paths the broad Sail tests leave untouched: the
 * plural {@code getNamespaces()} iteration and the wildcard branch of {@code removeStatements}.
 *
 * <p>{@code ProllySailTest} exercises the singular {@code getNamespace} / {@code removeNamespace} /
 * {@code clearNamespaces}, and exact-triple removal — but never iterates the namespace set, nor
 * deletes by a pattern with a {@code null} (wildcard) position.
 */
class ProllySailConnectionCoverageTest {

    private static long count(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        } finally {
            it.close();
        }
        return n;
    }

    @Test
    void getNamespaces_iterates_every_set_prefix() {
        ProllySail sail = new ProllySail();
        sail.init();
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://example.org/");
            conn.setNamespace("foaf", "http://xmlns.com/foaf/0.1/");
            conn.commit();

            Map<String, String> seen = new HashMap<>();
            try (CloseableIteration<? extends Namespace> it = conn.getNamespaces()) {
                while (it.hasNext()) {
                    Namespace ns = it.next();
                    seen.put(ns.getPrefix(), ns.getName());
                }
            }
            assertEquals("http://example.org/", seen.get("ex"));
            assertEquals("http://xmlns.com/foaf/0.1/", seen.get("foaf"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void removeStatements_with_a_wildcard_pattern_deletes_every_match() {
        ProllySail sail = new ProllySail();
        sail.init();
        try (SailConnection conn = sail.getConnection()) {
            ValueFactory vf = sail.getValueFactory();
            IRI s = vf.createIRI("urn:s");
            IRI p1 = vf.createIRI("urn:p1");
            IRI p2 = vf.createIRI("urn:p2");
            IRI o = vf.createIRI("urn:o");
            IRI other = vf.createIRI("urn:other");

            conn.begin();
            conn.addStatement(s, p1, o);
            conn.addStatement(s, p2, o);
            conn.addStatement(other, p1, o);
            conn.commit();

            // Wildcard delete — null predicate and object — drives the
            // scan-and-delete branch of removeStatementsInternal.
            conn.begin();
            conn.removeStatements(s, null, null);
            conn.commit();

            assertEquals(
                    0L,
                    count(conn.getStatements(s, null, null, false)),
                    "a wildcard pattern removes every statement matching the subject");
            assertEquals(
                    1L,
                    count(conn.getStatements(other, null, null, false)),
                    "statements outside the pattern are left untouched");
        } finally {
            sail.shutDown();
        }
    }
}
