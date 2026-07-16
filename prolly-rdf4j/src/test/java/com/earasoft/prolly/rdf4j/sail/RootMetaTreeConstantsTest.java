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

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RootMetaTree}'s standard name constants. These are part of the on-disk wire format —
 * drift here means an existing Sail can't find its dictionary, indexes, or provenance tree on
 * restart. The per-RDF4J-distribution version must agree on these literals.
 */
class RootMetaTreeConstantsTest {

    @Test
    void dict_name_pinned() {
        assertEquals(
                "dict",
                RootMetaTree.NAME_DICT,
                "dict tree key drift orphans every existing Sail's dictionary");
    }

    @Test
    void spoc_index_names_pinned() {
        // The four index keys are part of the planner's index-selection logic
        // and the SailMetrics counter names ("index.spoc.insert" etc.).
        assertEquals("spoc", RootMetaTree.NAME_SPOC);
        assertEquals("posc", RootMetaTree.NAME_POSC);
        assertEquals("ospc", RootMetaTree.NAME_OSPC);
        assertEquals("cspo", RootMetaTree.NAME_CSPO);
    }

    @Test
    void namespaces_name_pinned() {
        assertEquals("namespaces", RootMetaTree.NAME_NAMESPACES);
    }

    @Test
    void stats_name_pinned() {
        assertEquals("stats", RootMetaTree.NAME_STATS);
    }

    @Test
    void prefixes_name_pinned() {
        assertEquals("prefixes", RootMetaTree.NAME_PREFIXES);
    }

    @Test
    void provenance_name_pinned() {
        assertEquals("provenance", RootMetaTree.NAME_PROVENANCE);
    }

    @Test
    void provenance_events_name_pinned() {
        // Cross-distribution agreement: this is the key the enterprise event
        // log uses. OSS-vs-Enterprise stores must agree.
        assertEquals("provenance-events", RootMetaTree.NAME_PROVENANCE_EVENTS);
    }

    @Test
    void all_names_distinct() {
        // Two trees sharing a key would silently overwrite each other on
        // commit — pin uniqueness.
        Set<String> names = new HashSet<>();
        names.add(RootMetaTree.NAME_DICT);
        names.add(RootMetaTree.NAME_SPOC);
        names.add(RootMetaTree.NAME_POSC);
        names.add(RootMetaTree.NAME_OSPC);
        names.add(RootMetaTree.NAME_CSPO);
        names.add(RootMetaTree.NAME_NAMESPACES);
        names.add(RootMetaTree.NAME_STATS);
        names.add(RootMetaTree.NAME_PREFIXES);
        names.add(RootMetaTree.NAME_PROVENANCE);
        names.add(RootMetaTree.NAME_PROVENANCE_EVENTS);
        assertEquals(
                10,
                names.size(),
                "every RootMetaTree key must be distinct; a duplicate silently overwrites");
    }

    @Test
    void all_names_lowercase_no_whitespace() {
        // Convention check: keys must be filesystem-safe and stable.
        for (String name :
                new String[] {
                    RootMetaTree.NAME_DICT,
                    RootMetaTree.NAME_SPOC,
                    RootMetaTree.NAME_POSC,
                    RootMetaTree.NAME_OSPC,
                    RootMetaTree.NAME_CSPO,
                    RootMetaTree.NAME_NAMESPACES,
                    RootMetaTree.NAME_STATS,
                    RootMetaTree.NAME_PREFIXES,
                    RootMetaTree.NAME_PROVENANCE,
                    RootMetaTree.NAME_PROVENANCE_EVENTS
                }) {
            assertEquals(name.toLowerCase(), name, "name must be all lowercase: " + name);
            assertFalse(
                    name.matches(".*\\s.*"), "name must not contain whitespace: '" + name + "'");
            assertTrue(
                    name.matches("[a-z-]+"),
                    "name must be [a-z-]+ for filesystem safety: '" + name + "'");
        }
    }

    @Test
    void all_names_under_255_bytes() {
        // RootMetaTree.serialize writes name length as a single byte; names
        // longer than 255 throw at serialize time. Pin that the built-in
        // names stay safely under that.
        for (String name :
                new String[] {
                    RootMetaTree.NAME_DICT,
                    RootMetaTree.NAME_SPOC,
                    RootMetaTree.NAME_POSC,
                    RootMetaTree.NAME_OSPC,
                    RootMetaTree.NAME_CSPO,
                    RootMetaTree.NAME_NAMESPACES,
                    RootMetaTree.NAME_STATS,
                    RootMetaTree.NAME_PREFIXES,
                    RootMetaTree.NAME_PROVENANCE,
                    RootMetaTree.NAME_PROVENANCE_EVENTS
                }) {
            assertTrue(
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 256,
                    "name too long for u8 length prefix: " + name);
        }
    }
}
