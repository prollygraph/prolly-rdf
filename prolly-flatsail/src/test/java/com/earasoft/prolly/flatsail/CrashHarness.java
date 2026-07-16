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

import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.rocksdb.RocksDB;

/**
 * Forked-process helper for {@link RocksDbFlatSailCrashSafetyTest}. It opens a flat Sail at the
 * given directory, runs a scripted write sequence, then halts the JVM hard via {@link Runtime#halt}
 * — skipping shutdown hooks, the Sail's {@code shutDown()} and RocksDB's clean close. That
 * simulates a process crash: a committed transaction lives only in the RocksDB WAL at the moment of
 * death.
 *
 * <p>Not a test class — it is launched as a subprocess.
 *
 * <p>Modes (argument 1):
 *
 * <ul>
 *   <li>{@code commit-crash} — commit five statements, then crash.
 *   <li>{@code begin-crash} — add five statements but never commit, then crash.
 *   <li>{@code commit-begin-crash} — commit three, then begin + add two more (left uncommitted),
 *       then crash.
 * </ul>
 */
public final class CrashHarness {

    private CrashHarness() {}

    public static void main(String[] args) {
        RocksDB.loadLibrary();
        Path dir = Path.of(args[0]);
        String mode = args[1];

        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        SailConnection conn = sail.getConnection();
        ValueFactory vf = sail.getValueFactory();

        switch (mode) {
            case "commit-crash" -> {
                conn.begin();
                addRows(conn, vf, 0, 5);
                conn.commit();
            }
            case "begin-crash" -> {
                conn.begin();
                addRows(conn, vf, 0, 5);
                // deliberately no commit — the WriteBatch never reaches disk
            }
            case "commit-begin-crash" -> {
                conn.begin();
                addRows(conn, vf, 0, 3);
                conn.commit();
                conn.begin();
                addRows(conn, vf, 3, 5);
                // the second transaction is left in flight
            }
            default -> throw new IllegalArgumentException("unknown mode: " + mode);
        }

        // Hard crash: no conn.close(), no sail.shutDown(), no clean RocksDB
        // close. Whatever was committed lives only in the WAL right now.
        Runtime.getRuntime().halt(0);
    }

    private static void addRows(SailConnection conn, ValueFactory vf, int from, int to) {
        for (int i = from; i < to; i++) {
            conn.addStatement(
                    vf.createIRI("urn:s" + i), vf.createIRI("urn:p"), vf.createIRI("urn:o" + i));
        }
    }
}
