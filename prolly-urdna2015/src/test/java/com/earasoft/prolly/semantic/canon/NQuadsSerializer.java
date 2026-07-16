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
package com.earasoft.prolly.semantic.canon;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal N-Quads serializer for comparing canonicalizer output against expected W3C test-vector
 * strings.
 *
 * <p>Produces a deterministic byte-form by sorting the rendered lines. Pairs with {@link
 * NQuadsParser}; not a complete N-Quads 1.1 implementation.
 */
final class NQuadsSerializer {

    private NQuadsSerializer() {}

    /** Render quads to a canonical N-Quads string (sorted, LF-terminated). */
    static String serialize(List<QuadPattern> quads) {
        List<String> lines = new ArrayList<>(quads.size());
        for (QuadPattern q : quads) {
            lines.add(renderLine(q));
        }
        Collections.sort(lines);
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    private static String renderLine(QuadPattern q) {
        StringBuilder sb = new StringBuilder();
        appendTerm(sb, q.s().value());
        sb.append(' ');
        appendTerm(sb, q.p().value());
        sb.append(' ');
        appendObject(sb, q.o().value());
        if (!NQuadsParser.DEFAULT_GRAPH.equals(q.c())) {
            sb.append(' ');
            appendTerm(sb, q.c());
        }
        sb.append(" .");
        return sb.toString();
    }

    private static void appendTerm(StringBuilder sb, String value) {
        if (value.startsWith("_:")) {
            sb.append(value);
        } else {
            sb.append('<').append(value).append('>');
        }
    }

    /**
     * Object can be IRI, blank node, or literal; literals already carry their quoting from the
     * parser.
     */
    private static void appendObject(StringBuilder sb, String value) {
        if (value.startsWith("_:") || value.startsWith("\"")) {
            sb.append(value);
        } else {
            sb.append('<').append(value).append('>');
        }
    }
}
