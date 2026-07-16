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
package com.earasoft.prolly.rdf4j.term;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rot-guard for the {@code spec-compliance/} catalog — every test class and source file the catalog
 * cites must still exist (Phase 1 of {@code plans/spec-compliance-catalog-guard.md}).
 *
 * @implNote The catalog is the project's only defense against the W3C suites' structural blind spot
 *     (result comparison via {@code Value.equals} cannot see byte-identity / canonicalization bugs
 *     — the language-tag-case bug lived under 261 green tests for the life of the codec). Each
 *     invariant row names the <em>instrument that enters the failing regime</em> in its "Validated
 *     by" cell and the implementing code in "Port behavior" / "Where this lives". Those citations
 *     are hand-written prose: a renamed or deleted test/file silently leaves a dangling reference,
 *     and the catalog rots into a lie. This test is the deterministic guard against that — the
 *     {@link com.dolthub.prolly.NewcomerDocsLinkTest} pattern applied to the catalog.
 *     <p><b>Format-awareness (load-bearing).</b> The catalog cites three ways, and the parser
 *     handles each: a bare test-class name ({@code TermFaithfulnessGateTest}, optionally with a
 *     {@code .methodName} suffix), an abbreviated path with a {@code .../} ellipsis and a trailing
 *     {@code :line} ({@code prolly-codec/.../term/TermCodec.java:712}), and a full repo-relative
 *     path. Test-class refs are resolved to {@code <Class>.java}; {@code .java} file refs are
 *     resolved by <em>basename</em> (dropping the ellipsis prefix and the {@code :line}). Sibling
 *     {@code .md} references are deliberately NOT checked — they include intentional
 *     planned-backlog entries (the {@code format/} scaffold's "to fill in" list) and are
 *     doc-navigation, not the code/test instrument citations this guard protects. It checks
 *     PRESENCE, not correctness — the cited test is what asserts the behavior is right.
 *     <p><b>Collaborators:</b> reads {@code spec-compliance/**.md} (the catalog) and walks the repo
 *     tree (minus {@code target/}/{@code .git/}/etc.) to confirm each cited basename exists. Runs
 *     in {@code prolly-codec} (where the datatypes + most cited tests live) and therefore in the
 *     {@code build:full} CI gate. A second {@code @Test} below pins invariant-ID uniqueness + the
 *     table format on top of this (Phase 1 Step 2).
 */
class SpecComplianceCatalogLinkTest {

    /** Backtick-quoted tokens — the catalog cites code/files/tests in backticks. */
    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");

    /** A CamelCase JUnit test class name, optionally with a {@code .methodName} suffix. */
    private static final Pattern TEST_CLASS =
            Pattern.compile("^([A-Z][A-Za-z0-9_]*(?:Test|Property))(?:\\.[A-Za-z0-9_]+)?$");

    @Test
    void everyCatalogCitationResolves() throws IOException {
        Path repoRoot = findRepoRoot();
        Path catalog = repoRoot.resolve("spec-compliance");
        assertTrue(Files.isDirectory(catalog), "spec-compliance/ not found at " + catalog);

        Set<String> fileNames = repoFileNames(repoRoot);

        List<Path> docs;
        try (Stream<Path> walk = Files.walk(catalog)) {
            docs = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        assertTrue(!docs.isEmpty(), "no .md files found under " + catalog);

        Set<String> missing = new TreeSet<>();
        for (Path doc : docs) {
            String name = doc.getFileName().toString();
            Matcher m = BACKTICK.matcher(Files.readString(doc));
            while (m.find()) {
                String token = m.group(1).trim();
                String lastSegment = token.substring(token.lastIndexOf('/') + 1);
                if (token.contains(".java")) {
                    String base = basename(lastSegment);
                    if (base != null && !fileNames.contains(base)) {
                        missing.add(
                                name + ": file `" + token + "` (basename " + base + ") not found");
                    }
                    continue;
                }
                Matcher tc = TEST_CLASS.matcher(lastSegment);
                if (tc.matches()) {
                    String cls = tc.group(1) + ".java";
                    if (!fileNames.contains(cls)) {
                        missing.add(name + ": test `" + token + "` -> " + cls + " not found");
                    }
                }
            }
        }

        assertTrue(
                missing.isEmpty(),
                "spec-compliance catalog has dangling citations (rot) — fix the citation or "
                        + "restore the file:\n  "
                        + String.join("\n  ", missing));
    }

    /**
     * Pins the catalog's invariant-entry format (Phase 1 Step 2, D-5): every invariant under {@code
     * semantics/} has a UNIQUE {@code XXX-N} ID and a COMPLETE field table. The Step-1 rot-guard
     * parses these tables, so the format is a load-bearing contract — a half-written entry (missing
     * a field row) or a duplicated ID must fail here, not silently degrade the guard. (The ID is
     * backtick-wrapped in the heading; the {@code Invariant} field has parenthetical variants like
     * {@code (representability)} — both handled.)
     */
    @Test
    void everyInvariantHasUniqueIdAndRequiredFields() throws IOException {
        Path semantics = findRepoRoot().resolve("spec-compliance/semantics");
        List<Path> docs;
        try (Stream<Path> walk = Files.walk(semantics)) {
            docs = walk.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
        // ID lives in the h2 heading, optionally backtick-wrapped: `## `CANON-LANG-1` — title`.
        Pattern idHeading = Pattern.compile("^##\\s+`?([A-Z][A-Z0-9-]*-\\d+)`?");
        Pattern anyH2 = Pattern.compile("(?m)^##\\s");
        // "**Invariant" is a prefix (tolerates "**Invariant (representability)**" etc.).
        String[] required = {
            "**Spec**", "**Invariant", "**Port behavior**", "**Validated by**", "**W3C-visible?**"
        };

        Set<String> seen = new HashSet<>();
        Set<String> duplicate = new TreeSet<>();
        List<String> incomplete = new ArrayList<>();
        for (Path doc : docs) {
            String text = Files.readString(doc);
            List<Integer> starts = new ArrayList<>();
            Matcher h2 = anyH2.matcher(text);
            while (h2.find()) {
                starts.add(h2.start());
            }
            starts.add(text.length()); // sentinel so the last section has an end
            for (int i = 0; i < starts.size() - 1; i++) {
                String section = text.substring(starts.get(i), starts.get(i + 1));
                String headingLine = section.split("\n", 2)[0];
                Matcher idm = idHeading.matcher(headingLine);
                if (!idm.find()) {
                    continue; // an h2 that is not an invariant entry (prose section)
                }
                String id = idm.group(1);
                if (!seen.add(id)) {
                    duplicate.add(id);
                }
                List<String> missing = new ArrayList<>();
                for (String f : required) {
                    if (!section.contains(f)) {
                        missing.add(f);
                    }
                }
                if (!missing.isEmpty()) {
                    incomplete.add(doc.getFileName() + " [" + id + "] missing " + missing);
                }
            }
        }

        // Guard the guard: a parser that matches NOTHING must fail loudly, not pass vacuously.
        assertTrue(
                !seen.isEmpty(),
                "no invariant IDs found under spec-compliance/semantics/ — the heading format "
                        + "likely changed; update this parser");
        assertTrue(duplicate.isEmpty(), "duplicate invariant IDs (must be unique): " + duplicate);
        assertTrue(
                incomplete.isEmpty(),
                "invariant entries missing required field rows:\n  "
                        + String.join("\n  ", incomplete));
    }

    /** Last path segment with a trailing {@code :line} stripped, or null if it isn't a filename. */
    private static String basename(String lastSegment) {
        String base = lastSegment;
        int colon = base.indexOf(':');
        if (colon >= 0) {
            base = base.substring(0, colon);
        }
        return base.isEmpty() ? null : base;
    }

    /**
     * All regular-file basenames under {@code root}. Prunes (does not descend into) build / VCS /
     * dependency dirs — {@code Files.walk} would otherwise traverse a multi-thousand-file {@code
     * node_modules}/{@code target} and make the test slow.
     */
    private static Set<String> repoFileNames(Path root) throws IOException {
        Set<String> prune = Set.of("target", ".git", "node_modules", ".m2", ".idea", "dist");
        Set<String> names = new HashSet<>();
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return prune.contains(dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        names.add(file.getFileName().toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
        return names;
    }

    /** Walk up from the working directory to the repo root (the dir holding spec-compliance/). */
    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("spec-compliance"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "could not locate the repo root (a directory containing spec-compliance/) "
                        + "by walking up from "
                        + Paths.get("").toAbsolutePath());
    }
}
