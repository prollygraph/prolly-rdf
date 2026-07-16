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
package com.earasoft.prolly;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rot-guard for the ring's markdown: every RELATIVE link in every tracked {@code .md} must resolve.
 * The extraction de-linked 156 references to monorepo-private trees and remapped 12 to prolly-core
 * URLs (extract-prolly-rdf-repo Step 3) — this pins the result so a future doc edit cannot silently
 * reintroduce a dead or private-tree link. Absolute URLs are not checked (that would make the build
 * network-dependent); the {@code spec-compliance/} citation guard is separate ({@code
 * SpecComplianceCatalogLinkTest}).
 */
class RingDocsLinkTest {

    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#]+?)(?:#[^)]*)?\\)");

    @Test
    void everyRelativeMarkdownLinkResolves() throws IOException {
        Path root = repoRoot();
        List<String> broken = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/.git/"))
                    .forEach(
                            md -> {
                                final String text;
                                try {
                                    text = Files.readString(md);
                                } catch (IOException e) {
                                    broken.add(md + " (unreadable: " + e.getMessage() + ")");
                                    return;
                                }
                                Matcher m = LINK.matcher(text);
                                while (m.find()) {
                                    String href = m.group(1).strip();
                                    if (href.isEmpty()
                                            || href.startsWith("http")
                                            || href.startsWith("mailto:")) {
                                        continue;
                                    }
                                    if (!Files.exists(md.getParent().resolve(href))) {
                                        broken.add(root.relativize(md) + " -> " + href);
                                    }
                                }
                            });
        }
        assertTrue(
                broken.isEmpty(), "broken relative markdown links:\n" + String.join("\n", broken));
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("prolly-rdf-dependencies"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "ring repo root not found above " + Path.of("").toAbsolutePath());
        return p;
    }
}
