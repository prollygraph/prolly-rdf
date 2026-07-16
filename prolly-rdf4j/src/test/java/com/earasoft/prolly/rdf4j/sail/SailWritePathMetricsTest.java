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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 Steps 2+3 of {@code plans/observability-metrics-expansion.md}: the Sail records {@code
 * prolly.write.lock.wait} (a Timer around acquiring the single-writer lock, on every {@code begin})
 * and {@code prolly.commit.mutations} (a DistributionSummary of added+deleted per commit).
 */
class SailWritePathMetricsTest {

    @Test
    void recordsLockWaitAndCommitMutationCount() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), reg);
        Repository repo = new SailRepository(sail);
        repo.init();
        try {
            ValueFactory vf = repo.getValueFactory();
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(vf.createIRI("urn:a"), vf.createIRI("urn:p"), vf.createIRI("urn:b"));
                conn.add(vf.createIRI("urn:a"), vf.createIRI("urn:p"), vf.createIRI("urn:c"));
                conn.commit();
            }

            assertTrue(
                    reg.get("prolly.write.lock.wait").timer().count() >= 1,
                    "begin acquires the write lock → prolly.write.lock.wait recorded");

            DistributionSummary mutations = reg.get("prolly.commit.mutations").summary();
            assertEquals(1L, mutations.count(), "one commit recorded");
            assertEquals(2.0, mutations.totalAmount(), "two added statements in the commit");
        } finally {
            repo.shutDown();
        }
    }
}
