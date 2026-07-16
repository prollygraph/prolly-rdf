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
package com.earasoft.prolly.flatsail;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rot-guard for the repo's markdown documentation: every relative link and every cited repo path
 * must resolve against the working tree.
 *
 * <p>The docs under {@code docs/} (anatomy, foundations) cite source files and cross-link each
 * other; the READMEs link module docs. A rename or move silently breaks those references — this
 * test turns that rot into a build failure. It lives in this module only because the module is
 * small and fast to build; the subject is the whole repo's markdown.
 *
 * <p>Two checks:
 *
 * <ul>
 *   <li><b>Relative markdown links</b> ({@code [text](path)}) — the target file must exist,
 *       fragment ignored. External ({@code http}/{@code https}/{@code mailto}) links are skipped.
 *   <li><b>Backtick path citations</b> ({@code `module/src/.../Foo.java`}) — a token that starts
 *       with a top-level directory name and contains a slash must exist. Citations of the private
 *       monorepo are exempt when the line labels them (the literal text {@code private monorepo}),
 *       as are elided paths ({@code ...}) and repo-qualified engine paths ({@code prolly-core:…}).
 * </ul>
 */
class DocsLinkTest {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");

    @Test
    void relativeMarkdownLinksResolve() throws IOException {
        Path repoRoot = findRepoRoot();
        List<String> broken = new ArrayList<>();
        for (Path doc : markdownFiles(repoRoot)) {
            Matcher m = MARKDOWN_LINK.matcher(Files.readString(doc));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://")
                        || target.startsWith("https://")
                        || target.startsWith("mailto:")
                        || target.startsWith("#")) {
                    continue;
                }
                String withoutFragment = target.split("#", 2)[0];
                if (withoutFragment.isEmpty()) {
                    continue;
                }
                Path resolved = doc.getParent().resolve(withoutFragment).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(repoRoot.relativize(doc) + " -> " + target);
                }
            }
        }
        if (!broken.isEmpty()) {
            fail("broken relative markdown links:\n" + String.join("\n", broken));
        }
    }

    @Test
    void citedRepoPathsExist() throws IOException {
        Path repoRoot = findRepoRoot();
        Set<String> topLevel;
        try (Stream<Path> entries = Files.list(repoRoot)) {
            topLevel = entries.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
        List<String> broken = new ArrayList<>();
        for (Path doc : markdownFiles(repoRoot)) {
            String rel = repoRoot.relativize(doc).toString();
            if (rel.contains("docs/adr/")) {
                // Architecture decision records are point-in-time records; some cite the private
                // monorepo's work tracker by name (docs/README.md documents this). They are never
                // retroactively rewritten, so they are exempt from the citation check. Their
                // markdown LINKS are still checked by relativeMarkdownLinksResolve.
                continue;
            }
            if (rel.endsWith("cas-rebase-runbook.md")) {
                // The runbook's citations are its own deliverables — file paths its 12 numbered
                // implementation steps will create. Forward-looking by design.
                continue;
            }
            for (String line : Files.readAllLines(doc)) {
                if (line.contains("private monorepo")) {
                    continue; // labeled citation of a doc/plan that deliberately isn't here
                }
                Matcher m = BACKTICK.matcher(line);
                while (m.find()) {
                    String token = m.group(1).trim();
                    if (!looksLikeRepoPath(token, topLevel)) {
                        continue;
                    }
                    String clean =
                            token.endsWith("/") ? token.substring(0, token.length() - 1) : token;
                    // Prose cites paths from three natural bases: the repo root, the doc's own
                    // directory, and the doc's module root (e.g. a module README citing
                    // `docs/...` inside its module). Any of the three resolving counts.
                    Path moduleRoot = repoRoot.resolve(rel.split("/", 2)[0]);
                    boolean exists =
                            Files.exists(repoRoot.resolve(clean))
                                    || Files.exists(doc.getParent().resolve(clean))
                                    || (Files.isDirectory(moduleRoot)
                                            && Files.exists(moduleRoot.resolve(clean)));
                    if (!exists) {
                        broken.add(rel + " cites missing " + token);
                    }
                }
            }
        }
        if (!broken.isEmpty()) {
            fail("cited repo paths that do not exist:\n" + String.join("\n", broken));
        }
    }

    /** A slash-bearing token whose first segment is a real top-level entry, with no elision. */
    private static boolean looksLikeRepoPath(String token, Set<String> topLevel) {
        if (!token.contains("/") || token.contains("...") || token.contains(":")) {
            return false;
        }
        if (token.contains(" ") || token.contains("*") || token.contains("$")) {
            return false; // command lines, globs, shell fragments
        }
        if (token.startsWith("target/") || token.contains("/target/")) {
            return false; // build artifacts — transient by definition, absent after mvn clean
        }
        String first = token.split("/", 2)[0];
        return topLevel.contains(first);
    }

    private static List<Path> markdownFiles(Path repoRoot) throws IOException {
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            return walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/node_modules/"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .sorted()
                    .toList();
        }
    }

    /** Walks up from the module's working directory to the directory holding {@code .git}. */
    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "could not locate the repo root (.git) above " + Paths.get(""));
        return dir;
    }
}
