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

import java.util.List;
import org.eclipse.rdf4j.common.transaction.IsolationLevel;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Isolation-level <b>honesty</b> (test-strategy Step 14, S-4). Pins <i>exactly</i> which RDF4J
 * {@code IsolationLevel}s {@code ProllySail} advertises and its default, so that the sibling {@code
 * ProllySailIsolationLevelTest} (which runs RDF4J's contract suite over "each advertised level")
 * has a pass that <b>means something specific</b>: the suite probed exactly this ladder.
 *
 * <p>It also pins the consistency the inherited {@code AbstractSail} default violated — the default
 * level must be a <i>member</i> of the supported set (the inherited advertisement was {@code
 * [READ_UNCOMMITTED, SERIALIZABLE]} with a default of {@code READ_COMMITTED}, which is not in the
 * set). The Sail's runtime does not branch on the requested level — every transaction forks an
 * immutable {@code Snapshot} and writers serialize — so it delivers serializable-grade isolation
 * regardless; the advertisement is therefore metadata, and this test makes it a fixed contract.
 */
class ProllySailIsolationLevelHonestyTest {

    private static final List<IsolationLevel> EXPECTED =
            List.of(
                    IsolationLevels.READ_UNCOMMITTED,
                    IsolationLevels.READ_COMMITTED,
                    IsolationLevels.SNAPSHOT_READ,
                    IsolationLevels.SNAPSHOT,
                    IsolationLevels.SERIALIZABLE);

    @Test
    void advertises_exactly_the_pinned_ladder() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            assertEquals(
                    EXPECTED,
                    sail.getSupportedIsolationLevels(),
                    "the advertised isolation ladder is a fixed contract — RDF4J's suite probes exactly these");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void default_is_snapshot_and_is_a_member_of_the_supported_set() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            IsolationLevel def = sail.getDefaultIsolationLevel();
            assertEquals(
                    IsolationLevels.SNAPSHOT,
                    def,
                    "the default is snapshot isolation — the level a connection actually observes");
            assertTrue(
                    sail.getSupportedIsolationLevels().contains(def),
                    "the default MUST be a member of the supported set (the inherited AbstractSail default was not)");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void every_advertised_level_can_actually_be_begun() {
        // Runtime is level-independent (always the snapshot fork), so begin(level) must succeed for
        // every advertised level — the honest counterpart to "we support these".
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            for (IsolationLevel level : sail.getSupportedIsolationLevels()) {
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin(level);
                    conn.rollback();
                }
            }
        } finally {
            sail.shutDown();
        }
    }
}
