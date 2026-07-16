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
package com.earasoft.prolly.rdf4j.compliance;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Factory for a fresh, initialized, empty {@code ProllySail}-backed {@link SailRepository} — the
 * unit RDF4J's conformance harness asks for via {@code newRepository()} (once per test; the harness
 * clears and shuts it down afterwards).
 *
 * <p><b>Storage backend — deviation from plan §10.3.</b> The plan called for a RocksDB-backed
 * store. But {@code newRepository()} is invoked <em>once per W3C test</em> — hundreds of times per
 * run — so per-test RocksDB instances plus temp directories would be slow and leak native handles.
 * SPARQL conformance is storage-backend-agnostic: query evaluation walks the same prolly-tree /
 * index code regardless of the {@code NodeStore} implementation, and durable persistence is already
 * covered by {@code prolly-rdf4j-e2e} / {@code DiskPersistenceTest}. So the conformance suite uses
 * the no-arg in-memory {@code ProllySail} (in-memory {@code NodeStore} + in-memory {@code
 * CommitLog}/{@code RefsStore}): faster, no temp-dir leak, identical SPARQL semantics.
 */
final class ProllyComplianceRepository {

    private ProllyComplianceRepository() {}

    /** A fresh, {@code init()}-ed, empty repository ready for the harness. */
    static SailRepository fresh() {
        SailRepository repo = new SailRepository(new ProllySail());
        repo.init();
        return repo;
    }
}
