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

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/**
 *
 *
 * <h3>RocksDB → Prometheus stats exporter — the compaction/IO ground truth.</h3>
 *
 * <p>External signals (disk-space, device IO) are distorted by ZFS (compression, copy-on-write, ARC
 * — see {@code newcomer-docs/advanced-topics/io-and-zfs.md}). The <i>logical</i> truth — how much
 * each LSM level holds, whether compaction is backed up, how big the memtables/block-cache are —
 * lives inside RocksDB. This tool opens a RocksDB directory <b>read-only</b> (which does <i>not</i>
 * take the write lock, so it can poll a store that a benchmark is actively writing) and serves the
 * key {@code db.getProperty(...)} gauges in Prometheus text format. node_exporter is system-wide;
 * this is the per-store engine view that complements it and {@link RocksPerfProbe} (per-operation
 * PerfContext counters).
 *
 * <p><b>Run:</b> {@code java … -cp <cp> RocksStatsExporter <rocksdb-dir> [port=9295]}, then add a
 * Prometheus scrape job for {@code localhost:9295}. Re-opens the DB read-only on every scrape so
 * the numbers stay fresh as compaction runs (cheap at a 5 s interval; for very high-frequency
 * polling, a RocksDB <i>secondary</i> instance with {@code tryCatchUpWithPrimary()} would avoid the
 * reopen). Bound to localhost only.
 */
public final class RocksStatsExporter {

    /** Numeric CF-level properties worth a gauge each. */
    private static final String[] CF_PROPS = {
        "rocksdb.estimate-num-keys",
        "rocksdb.total-sst-files-size",
        "rocksdb.live-sst-files-size",
        "rocksdb.size-all-mem-tables",
        "rocksdb.cur-size-all-mem-tables",
        "rocksdb.estimate-pending-compaction-bytes",
        "rocksdb.compaction-pending",
        "rocksdb.num-running-compactions",
        "rocksdb.num-running-flushes",
        "rocksdb.num-live-versions",
        "rocksdb.estimate-live-data-size",
        "rocksdb.block-cache-usage",
        "rocksdb.block-cache-capacity",
    };

    private static final int MAX_LEVEL = 6;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RocksStatsExporter <rocksdb-dir> [port=9295]");
            System.exit(2);
        }
        String path = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9295;
        RocksDB.loadLibrary();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(
                "/metrics",
                ex -> {
                    byte[] body = scrape(path).getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders()
                            .set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                });
        server.setExecutor(null);
        server.start();
        System.out.println(
                "RocksStatsExporter on http://127.0.0.1:" + port + "/metrics  db=" + path);
    }

    /**
     * Open the DB read-only, read every property for every column family, format as Prometheus
     * text.
     */
    static String scrape(String path) {
        StringBuilder sb = new StringBuilder(4096);
        long t0 = System.nanoTime();
        int up = 0;
        try (Options listOpts = new Options()) {
            List<byte[]> cfNames = RocksDB.listColumnFamilies(listOpts, path);
            if (cfNames.isEmpty()) {
                cfNames = new ArrayList<>();
                cfNames.add(RocksDB.DEFAULT_COLUMN_FAMILY);
            }
            List<ColumnFamilyDescriptor> descs = new ArrayList<>();
            for (byte[] n : cfNames) {
                descs.add(new ColumnFamilyDescriptor(n));
            }
            List<ColumnFamilyHandle> handles = new ArrayList<>();
            try (DBOptions dbo = new DBOptions();
                    RocksDB db = RocksDB.openReadOnly(dbo, path, descs, handles)) {
                up = 1;
                try {
                    for (int i = 0; i < handles.size(); i++) {
                        String cf = new String(cfNames.get(i), StandardCharsets.UTF_8);
                        String lbl = "{path=\"" + esc(path) + "\",cf=\"" + esc(cf) + "\"}";
                        for (String p : CF_PROPS) {
                            appendIfNumeric(sb, metricName(p), lbl, prop(db, handles.get(i), p));
                        }
                        for (int lvl = 0; lvl <= MAX_LEVEL; lvl++) {
                            String v = prop(db, handles.get(i), "rocksdb.num-files-at-level" + lvl);
                            if (isNumeric(v)) {
                                sb.append("rocksdb_num_files_at_level{path=\"")
                                        .append(esc(path))
                                        .append("\",cf=\"")
                                        .append(esc(cf))
                                        .append("\",level=\"")
                                        .append(lvl)
                                        .append("\"} ")
                                        .append(v)
                                        .append('\n');
                            }
                        }
                    }
                } finally {
                    for (ColumnFamilyHandle h : handles) {
                        h.close();
                    }
                }
            }
        } catch (RocksDBException e) {
            // leave up=0; emit the up gauge below so a scrape always returns
        }
        sb.append("rocksdb_exporter_up{path=\"")
                .append(esc(path))
                .append("\"} ")
                .append(up)
                .append('\n');
        sb.append("rocksdb_exporter_scrape_seconds{path=\"")
                .append(esc(path))
                .append("\"} ")
                .append((System.nanoTime() - t0) / 1e9)
                .append('\n');
        return sb.toString();
    }

    private static String prop(RocksDB db, ColumnFamilyHandle h, String name) {
        try {
            return db.getProperty(h, name);
        } catch (RocksDBException e) {
            return null;
        }
    }

    private static void appendIfNumeric(StringBuilder sb, String metric, String lbl, String v) {
        if (isNumeric(v)) {
            sb.append(metric).append(lbl).append(' ').append(v).append('\n');
        }
    }

    private static boolean isNumeric(String v) {
        if (v == null || v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static String metricName(String prop) {
        return prop.replace('.', '_')
                .replace('-', '_'); // rocksdb.estimate-num-keys -> rocksdb_estimate_num_keys
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private RocksStatsExporter() {}
}
