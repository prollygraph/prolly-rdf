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
package com.earasoft.prolly.rdf4j.bench;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Not a unit test — a profiling probe. Commits a 5000-triple batch through a ProllySail wired with
 * a {@link SimpleMeterRegistry} and prints the per-phase breakdown {@code commitInternal} already
 * records (dict / indexes / prefixes / namespaces / stats). Run: {@code mvn -pl prolly-rdf4j test
 * -Dtest=IngestProfileTest}.
 */
class IngestProfileTest {

    @Test
    void profile_a_5000_triple_commit() {
        int n = 5000;
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), metrics);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            long t0 = System.nanoTime();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                for (int i = 0; i < n; i++) {
                    conn.addStatement(
                            vf.createIRI("urn:bench:s:" + i),
                            vf.createIRI("urn:bench:p"),
                            vf.createIRI("urn:bench:o:" + i));
                }
                conn.commit();
            }
            long wall = System.nanoTime() - t0;

            System.out.println("=== ingest profile: " + n + "-triple commit ===");
            System.out.printf("  wall total           %8.2f ms%n", wall / 1e6);
            for (String phase :
                    new String[] {
                        "sail.add.encode", "sail.add.insert",
                        "sail.commit.tables", "sail.commit.prefixes"
                    }) {
                var timer = metrics.find(phase).timer();
                double totalNanos = timer == null ? 0d : timer.totalTime(TimeUnit.NANOSECONDS);
                System.out.printf("  %-22s %8.2f ms%n", phase, totalNanos / 1e6);
            }
        } finally {
            sail.shutDown();
        }
    }
}
