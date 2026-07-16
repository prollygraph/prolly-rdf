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
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.testsuite.sail.SailIsolationLevelTest;

/**
 * Runs RDF4J's transaction-isolation contract suite against a {@code ProllySail} (plan 10, §10.9).
 *
 * <p>{@link SailIsolationLevelTest} probes each {@code IsolationLevel} the Sail advertises —
 * read-uncommitted, read-committed, snapshot, serializable — for the guarantees that level promises
 * (dirty reads, non-repeatable reads, lost updates, ...).
 */
public class ProllySailIsolationLevelTest extends SailIsolationLevelTest {

    @Override
    protected Sail createSail() throws SailException {
        return new ProllySail();
    }
}
