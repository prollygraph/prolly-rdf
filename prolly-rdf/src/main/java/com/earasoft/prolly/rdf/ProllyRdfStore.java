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
package com.earasoft.prolly.rdf;

import java.util.Iterator;

/**
 *
 *
 * <h3>Adapter-agnostic facade over the versioned RDF substrate.</h3>
 *
 * <p>Provides a stable, library-neutral surface that both {@code prolly-jena} and {@code
 * prolly-rdf4j} can delegate to. Each adapter handles its own {@code Node}/{@code Value} ↔ {@code
 * String} encoding (since Jena and RDF4J use different term types); this facade works entirely in
 * opaque UTF-8 strings.
 *
 * <h4>Encoding contract (v1, lossy)</h4>
 *
 * <p>The adapter is responsible for encoding its native term types as strings. The v1 convention
 * used by both adapters:
 *
 * <ul>
 *   <li>IRIs → the IRI string verbatim ({@code "http://example.com/a"}).
 *   <li>Blank nodes → {@code "_:label"}.
 *   <li>Literals → {@code "\"lexical-form\""} (quoted; no datatype, no lang tag in v1; lossy).
 * </ul>
 *
 * <p>Round-tripping is byte-exact for IRIs and blank nodes; literal datatype + language information
 * is lost. This is acceptable for v1 SPARQL workloads that don't depend on typed literal semantics;
 * v2 will extend with a {@code Term} sealed type that preserves all three.
 *
 * <h4>Transaction model</h4>
 *
 * <p>{@link #add(String, String, String, String)} and {@link #remove(String, String, String,
 * String)} buffer mutations until {@link #flush(String, String, String)} is called, which produces
 * a single new commit on the target branch. Reads via {@link #find(String, String, String, String,
 * String)} return data from the *committed* state — the adapter is responsible for calling {@code
 * flush} before reads if read-your-writes is required.
 *
 * <h4>Branch model</h4>
 *
 * <p>The facade is branch-agnostic — every method takes the branch name explicitly. Adapters that
 * conceptually own a single "current branch" (Jena's {@code Graph}, RDF4J's {@code Sail}) hold the
 * branch state themselves and pass it on each call.
 */
public interface ProllyRdfStore {

    /**
     * Buffer an addition of a fully-specified quad. All four positions must be non-null. The
     * mutation is not visible to readers until {@link #flush(String, String, String)} is called.
     */
    void add(String subject, String predicate, String object, String context);

    /** Buffer a removal of a fully-specified quad. All four positions must be non-null. */
    void remove(String subject, String predicate, String object, String context);

    /** Whether any uncommitted mutations are buffered. */
    boolean hasPending();

    /** Discard all buffered mutations. */
    void clearPending();

    /**
     * Flush buffered mutations as a single new commit on the target branch. Returns true if the
     * CAS-style branch update succeeded, false if a concurrent writer raced and the caller should
     * retry. Returns true (no-op) if nothing is pending.
     */
    boolean flush(String branch, String author, String message);

    /**
     * Find quads matching the pattern on the given branch. A {@code null} in any position is a
     * wildcard. Returns an iterator yielding {@code String[]} of length 4: {@code [subject,
     * predicate, object, context]}.
     *
     * <p>This method does NOT auto-flush. Callers that need to see their own pending writes must
     * call {@link #flush(String, String, String)} first.
     */
    Iterator<String[]> find(
            String subject, String predicate, String object, String context, String branch);

    /** Number of quads on the named branch. Pending mutations are not counted. */
    long size(String branch);

    // ---- v1 encoding helpers (adapter-shared) ---------------------------

    /** Encode a literal lexical form as the v1 stored string. */
    static String encodeLiteral(String lexical) {
        return "\"" + lexical + "\"";
    }

    /** Encode a blank-node label as the v1 stored string. */
    static String encodeBlankNode(String label) {
        return "_:" + label;
    }

    /** True if the stored string is a v1-encoded literal. */
    static boolean isEncodedLiteral(String stored) {
        return stored != null
                && stored.length() >= 2
                && stored.charAt(0) == '"'
                && stored.charAt(stored.length() - 1) == '"';
    }

    /** True if the stored string is a v1-encoded blank node. */
    static boolean isEncodedBlankNode(String stored) {
        return stored != null && stored.startsWith("_:");
    }

    /** Extract the lexical form from a v1-encoded literal. */
    static String decodeLiteralLexical(String stored) {
        return stored.substring(1, stored.length() - 1);
    }

    /** Extract the label from a v1-encoded blank node. */
    static String decodeBlankNodeLabel(String stored) {
        return stored.substring(2);
    }
}
