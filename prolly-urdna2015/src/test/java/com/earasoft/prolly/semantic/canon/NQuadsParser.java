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
import java.util.List;

/**
 * Minimal N-Quads parser sufficient for W3C RDFC-1.0 test vectors.
 *
 * <p>Implements a pragmatic subset of N-Quads 1.1 (W3C, 2014):
 *
 * <ul>
 *   <li>IRIs in {@code <...>} form → preserved as the bare URI value.
 *   <li>Blank nodes in {@code _:label} form → preserved verbatim.
 *   <li>Literals in {@code "..."} form, with optional datatype ({@code ^^<...>}) or language tag
 *       ({@code @lang}) → preserved as a single literal-with-syntax string (e.g. {@code
 *       "100"^^<http://www.w3.org/2001/XMLSchema#integer>}).
 *   <li>Default graph (no 4th term) → maps to the literal string {@code "DEFAULT_GRAPH"} as our
 *       QuadPattern requires a context.
 *   <li>Comments ({@code #}) and blank lines skipped.
 * </ul>
 *
 * <p>Not a complete N-Quads parser. Does not handle: numeric literal shortcuts ({@code 100}),
 * escape-sequence decoding ({@code \\n}, {@code \\u00FC}), full Turtle subset. For W3C-vector smoke
 * tests this subset is sufficient; round-tripping more complex inputs is future work.
 */
final class NQuadsParser {

    private NQuadsParser() {}

    static final String DEFAULT_GRAPH = "DEFAULT_GRAPH";

    /** Parse an N-Quads-formatted string into a list of quads. */
    static List<QuadPattern> parse(String text) {
        List<QuadPattern> out = new ArrayList<>();
        int line = 0;
        for (String raw : text.split("\\R")) {
            line++;
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;

            // Strip trailing " ." (the statement terminator).
            if (!t.endsWith(".")) {
                throw new ParseException("line " + line + ": missing trailing '.': " + raw);
            }
            t = t.substring(0, t.length() - 1).trim();

            QuadPattern q = parseLine(t, line);
            out.add(q);
        }
        return out;
    }

    private static QuadPattern parseLine(String line, int lineNo) {
        List<String> terms = new ArrayList<>(4);
        int i = 0;
        while (i < line.length() && terms.size() < 4) {
            i = skipWhitespace(line, i);
            if (i >= line.length()) break;
            int[] span = readTerm(line, i, lineNo);
            terms.add(line.substring(span[0], span[1]));
            i = span[1];
        }
        if (terms.size() < 3) {
            throw new ParseException("line " + lineNo + ": expected at least 3 terms: " + line);
        }
        String s = unwrap(terms.get(0));
        String p = unwrap(terms.get(1));
        String o = unwrapObject(terms.get(2));
        String g = terms.size() == 4 ? unwrap(terms.get(3)) : DEFAULT_GRAPH;
        return QuadPattern.of(s, p, o, g);
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Reads one term starting at {@code start}; returns {@code [tokenStart, tokenEnd]}. */
    private static int[] readTerm(String s, int start, int lineNo) {
        char c = s.charAt(start);
        if (c == '<') {
            int end = s.indexOf('>', start);
            if (end < 0) throw new ParseException("line " + lineNo + ": unterminated IRI");
            return new int[] {start, end + 1};
        }
        if (c == '_' && start + 1 < s.length() && s.charAt(start + 1) == ':') {
            int end = start + 2;
            while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
            return new int[] {start, end};
        }
        if (c == '"') {
            // Find the closing quote, then optionally consume ^^<...> or @lang.
            int end = start + 1;
            while (end < s.length() && s.charAt(end) != '"') {
                if (s.charAt(end) == '\\' && end + 1 < s.length()) end++;
                end++;
            }
            if (end >= s.length())
                throw new ParseException("line " + lineNo + ": unterminated literal");
            end++; // include closing quote
            // Optional ^^<datatype>
            if (end + 1 < s.length() && s.charAt(end) == '^' && s.charAt(end + 1) == '^') {
                end += 2;
                if (end < s.length() && s.charAt(end) == '<') {
                    int dtEnd = s.indexOf('>', end);
                    if (dtEnd < 0)
                        throw new ParseException("line " + lineNo + ": unterminated datatype IRI");
                    end = dtEnd + 1;
                }
            }
            // Optional @lang
            else if (end < s.length() && s.charAt(end) == '@') {
                end++;
                while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
            }
            return new int[] {start, end};
        }
        throw new ParseException(
                "line "
                        + lineNo
                        + ": unexpected character '"
                        + c
                        + "' at column "
                        + start
                        + " in: "
                        + s);
    }

    /** Strip surrounding {@code <>} from an IRI term; leave blank nodes and literals alone. */
    private static String unwrap(String term) {
        if (term.startsWith("<") && term.endsWith(">")) {
            return term.substring(1, term.length() - 1);
        }
        return term;
    }

    /** Object position can also be a literal; preserve the full lexical form. */
    private static String unwrapObject(String term) {
        if (term.startsWith("<") && term.endsWith(">")) {
            return term.substring(1, term.length() - 1);
        }
        return term;
    }

    static class ParseException extends RuntimeException {
        ParseException(String msg) {
            super(msg);
        }
    }
}
