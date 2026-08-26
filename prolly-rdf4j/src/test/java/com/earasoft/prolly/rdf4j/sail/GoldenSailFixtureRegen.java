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

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.storage.FileNodeStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * REGENERATOR for the golden persisted-sail fixture (roadmap T7) — deliberately inert in normal
 * runs ({@code -Dgolden.regen=true} activates it; the regen script {@code
 * quarkus-ontology-editor/dev-scripts/regen-golden-fixtures.sh} is the intended driver).
 *
 * <p><b>The protocol (also in the fixture README):</b> a failing {@link GoldenSailOpenTest} means
 * the on-disk format changed. EITHER fix the code, OR — for a deliberate pre-1.0 format break —
 * regenerate via this class, review the OPEN-TEST's semantic assertions (commit ids embed
 * timestamps, so raw bytes churn on every regen; the byte diff is NOT the review surface), and
 * write the CHANGELOG format note. Never regenerate to silence a failure you don't understand.
 */
class GoldenSailFixtureRegen {

    static final Path FIXTURE = Path.of("src", "test", "resources", "golden-sail", "v-current");

    @Test
    void regenerate() throws Exception {
        Assumptions.assumeTrue(
                Boolean.getBoolean("golden.regen"),
                "regen tool — run via dev-scripts/regen-golden-fixtures.sh");
        if (Files.exists(FIXTURE)) {
            try (Stream<Path> walk = Files.walk(FIXTURE)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(FIXTURE);
        try (FileNodeStore store = new FileNodeStore(FIXTURE.resolve("chunks"))) {
            ProllySail sail =
                    new ProllySail(
                            store,
                            new HeapBufferPool(),
                            RootMetaTreeStore.beside(FIXTURE),
                            CommitLog.beside(FIXTURE),
                            RefsStore.beside(FIXTURE),
                            false);
            SailRepository repo = new SailRepository(sail);
            repo.init();
            ValueFactory vf = repo.getValueFactory();
            try (SailRepositoryConnection con = repo.getConnection()) {
                con.begin();
                GoldenSailOpenTest.addGoldenStatements(con, vf);
                con.commit();
            }
            repo.shutDown();
        }
        Files.writeString(
                FIXTURE.resolve("README.md"),
                """
                # Golden persisted-sail fixture (v-current)

                Written by GoldenSailFixtureRegen against the code at regen time; opened and
                semantically asserted by GoldenSailOpenTest on every build. A failing open test
                means THE ON-DISK FORMAT CHANGED: fix the code, or for a deliberate pre-1.0
                format break regenerate (mvn -pl prolly-rdf4j test -Dtest=GoldenSailFixtureRegen
                -Dgolden.regen=true -Djacoco.skip=true), review the open-test assertions (commit
                ids embed timestamps — bytes churn every regen; semantics are the review
                surface), and write the CHANGELOG format note. Contents: FileNodeStore chunks/,
                RootMetaTree + CommitLog + Refs sidecars; ten statements covering the newest
                format surface (an RDF-star quoted triple, a custom-datatype literal, a
                lang-string, a named graph).
                """,
                StandardCharsets.UTF_8);
    }
}
