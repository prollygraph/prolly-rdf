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

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/**
 * RocksDB lifecycle for {@code RocksDbFlatSail}: opens one database hosting the flat Sail's seven
 * column families and hands out their handles.
 *
 * <ul>
 *   <li>{@link #CF_DICT_FWD} — {@code TermId} → encoded RDF-term bytes.
 *   <li>{@link #CF_DICT_REV} — term-hash → {@code TermId}.
 *   <li>{@link #CF_SPOC}/{@link #CF_POSC}/{@link #CF_OSPC}/{@link #CF_CSPO} — the four 32-byte
 *       permutation indexes; keys only, empty values.
 *   <li>{@link #CF_NS} — namespace prefix → IRI.
 * </ul>
 *
 * <p>RocksDB's mandatory {@code default} column family is opened too but unused by the flat Sail.
 * {@link #open} rediscovers whatever column families an existing database already holds (via {@code
 * listColumnFamilies}) and creates any still-missing one, so a fresh directory and a reopened one
 * both work.
 *
 * <h3>Ownership &amp; close order</h3>
 *
 * <p>This object owns the {@link RocksDB} and every column-family handle. Its {@link #close()}
 * disposes the handles, then the database, then the option objects — the order RocksDB's JNI
 * bindings require.
 */
public final class RocksFlatStore implements AutoCloseable {

    /** Forward dictionary: {@code TermId} → encoded RDF-term bytes. */
    public static final String CF_DICT_FWD = "dict-fwd";

    /** Reverse dictionary: term-hash → {@code TermId}. */
    public static final String CF_DICT_REV = "dict-rev";

    /** SPOC permutation index. */
    public static final String CF_SPOC = "spoc";

    /** POSC permutation index. */
    public static final String CF_POSC = "posc";

    /** OSPC permutation index. */
    public static final String CF_OSPC = "ospc";

    /** CSPO permutation index. */
    public static final String CF_CSPO = "cspo";

    /** Namespaces: prefix → IRI. */
    public static final String CF_NS = "ns";

    /** The seven flat-Sail column families, in a stable declaration order. */
    public static final List<String> COLUMN_FAMILIES =
            List.of(CF_DICT_FWD, CF_DICT_REV, CF_SPOC, CF_POSC, CF_OSPC, CF_CSPO, CF_NS);

    private static final String DEFAULT_CF = "default";

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final Map<String, ColumnFamilyHandle> handles;
    private final List<ColumnFamilyOptions> cfOptions;

    /**
     * RocksDB's full statistics recorder — <b>opt-in</b> via {@code
     * -Dprolly.rocksdb.statistics=true} (default off: always-on ticker counting has measurable
     * overhead). The same flag and semantics as {@code RocksNodeStore}'s recorder, so one property
     * lights up store-health statistics on BOTH Sails' storage (rocksdb-perf-instrumentation Step
     * 6). {@code null} when disabled.
     */
    private final org.rocksdb.Statistics statistics;

    private boolean closed;

    private RocksFlatStore(
            RocksDB db,
            DBOptions dbOptions,
            Map<String, ColumnFamilyHandle> handles,
            List<ColumnFamilyOptions> cfOptions,
            org.rocksdb.Statistics statistics) {
        this.db = db;
        this.dbOptions = dbOptions;
        this.handles = handles;
        this.cfOptions = cfOptions;
        this.statistics = statistics;
    }

    /**
     * Open the flat-Sail database at {@code path}, ensuring the {@code default} column family plus
     * all seven {@link #COLUMN_FAMILIES} exist.
     */
    public static RocksFlatStore open(String path) throws RocksDBException {
        // An existing database must be reopened with descriptors for every CF
        // it already holds, or RocksDB refuses to open. Union those with our
        // seven so a fresh directory and a reopened one both succeed.
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(DEFAULT_CF);
        try (Options probe = new Options()) {
            for (byte[] existing : RocksDB.listColumnFamilies(probe, path)) {
                names.add(new String(existing, StandardCharsets.UTF_8));
            }
        }
        names.addAll(COLUMN_FAMILIES);

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>(names.size());
        List<ColumnFamilyOptions> cfOptions = new ArrayList<>(names.size());
        for (String name : names) {
            ColumnFamilyOptions opts = new ColumnFamilyOptions();
            cfOptions.add(opts);
            descriptors.add(
                    new ColumnFamilyDescriptor(name.getBytes(StandardCharsets.UTF_8), opts));
        }

        DBOptions dbOptions =
                new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
        org.rocksdb.Statistics statistics =
                Boolean.getBoolean("prolly.rocksdb.statistics")
                        ? new org.rocksdb.Statistics()
                        : null;
        if (statistics != null) {
            dbOptions.setStatistics(statistics);
        }
        List<ColumnFamilyHandle> handleList = new ArrayList<>(names.size());
        try {
            RocksDB db = RocksDB.open(dbOptions, path, descriptors, handleList);
            Map<String, ColumnFamilyHandle> handles = new LinkedHashMap<>();
            int i = 0;
            for (String name : names) {
                handles.put(name, handleList.get(i++));
            }
            return new RocksFlatStore(db, dbOptions, handles, cfOptions, statistics);
        } catch (RocksDBException e) {
            // A failed open leaks no native handles — release the options.
            dbOptions.close();
            for (ColumnFamilyOptions opts : cfOptions) {
                opts.close();
            }
            if (statistics != null) {
                statistics.close();
            }
            throw e;
        }
    }

    /**
     * The full RocksDB store-health dump — the flatsail mirror of {@code
     * RocksNodeStore.rocksDbFullStats()} (rocksdb-perf-instrumentation Step 7): {@code
     * rocksdb.stats} (the per-level compaction table — files, sizes, read/write GB,
     * <b>write-amplification</b>, stalls), {@code rocksdb.cfstats} + {@code rocksdb.levelstats},
     * and, when {@code -Dprolly.rocksdb.statistics=true} was set at open, the full {@code
     * Statistics} recorder (every ticker + histogram: block-cache hit/miss, bloom usefulness, bytes
     * read/written, {@code DB_GET}/{@code DB_SEEK}/{@code SST_READ_MICROS} histograms). Verbose by
     * design — an end-of-run dump, not a per-query line (per-query attribution is {@code
     * RocksPerfProbe}'s job).
     */
    public String rocksDbFullStats() {
        StringBuilder sb = new StringBuilder();
        sb.append(strProp("rocksdb.stats"));
        sb.append('\n').append(strProp("rocksdb.cfstats"));
        sb.append('\n').append(strProp("rocksdb.levelstats"));
        if (statistics != null) {
            sb.append("\n=== rocksdb Statistics (all tickers + histograms) ===\n");
            sb.append(statistics);
        }
        return sb.toString();
    }

    /** A string-valued DB-level RocksDB property, labelled; {@code ""} if absent. */
    private String strProp(String name) {
        try {
            return "=== " + name + " ===\n" + db.getProperty(name);
        } catch (RocksDBException e) {
            return "";
        }
    }

    /** The underlying database — for callers that need raw RocksDB access. */
    public RocksDB db() {
        return db;
    }

    /** Handle for a named column family; one of {@link #COLUMN_FAMILIES}. */
    public ColumnFamilyHandle columnFamily(String name) {
        ColumnFamilyHandle handle = handles.get(name);
        if (handle == null) {
            throw new IllegalArgumentException(
                    "no such column family: " + name + " (opened: " + handles.keySet() + ")");
        }
        return handle;
    }

    /** Forward-dictionary column family ({@code TermId} → term bytes). */
    public ColumnFamilyHandle dictForward() {
        return columnFamily(CF_DICT_FWD);
    }

    /** Reverse-dictionary column family (term-hash → {@code TermId}). */
    public ColumnFamilyHandle dictReverse() {
        return columnFamily(CF_DICT_REV);
    }

    /** Namespaces column family (prefix → IRI). */
    public ColumnFamilyHandle namespaces() {
        return columnFamily(CF_NS);
    }

    /** The permutation-index column family for {@code order}. */
    public ColumnFamilyHandle index(QuadOrder order) {
        return columnFamily(
                switch (order) {
                    case SPOC -> CF_SPOC;
                    case POSC -> CF_POSC;
                    case OSPC -> CF_OSPC;
                    case CSPO -> CF_CSPO;
                });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Column-family handles before the DB, options last — the order
        // RocksDB's JNI bindings require to avoid use-after-free on native peers.
        for (ColumnFamilyHandle handle : handles.values()) {
            handle.close();
        }
        db.close();
        dbOptions.close();
        for (ColumnFamilyOptions opts : cfOptions) {
            opts.close();
        }
        if (statistics != null) {
            statistics.close();
        }
    }
}
