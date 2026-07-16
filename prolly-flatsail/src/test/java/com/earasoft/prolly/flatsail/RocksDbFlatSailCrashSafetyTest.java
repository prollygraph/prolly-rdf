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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Crash-safety / WAL-recovery coverage (Step 12). A forked JVM ({@link CrashHarness}) writes and
 * then {@link Runtime#halt}s — a real process death, skipping every clean-shutdown path. This test
 * then reopens the directory and checks what survived.
 */
class RocksDbFlatSailCrashSafetyTest {
    static {
        RocksDB.loadLibrary();
    }

    /** Run {@link CrashHarness} in a forked JVM that crashes itself. */
    private static void crash(Path dir, String mode) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "--enable-preview", // FFM is preview on Java 21
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        System.getProperty("java.class.path"),
                        CrashHarness.class.getName(),
                        dir.toString(),
                        mode);
        pb.inheritIO();
        Process process = pb.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "crash harness timed out");
    }

    /** Reopen the directory in this JVM and report the committed statement count. */
    private static long sizeAfterReopen(Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try (SailConnection conn = sail.getConnection()) {
            return conn.size();
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void committed_data_survives_a_crash(@TempDir Path dir) throws Exception {
        crash(dir, "commit-crash");
        assertEquals(
                5L,
                sizeAfterReopen(dir),
                "a committed transaction must survive a crash — recovered from the WAL");
    }

    @Test
    void an_uncommitted_transaction_leaves_no_trace(@TempDir Path dir) throws Exception {
        crash(dir, "begin-crash");
        assertEquals(
                0L,
                sizeAfterReopen(dir),
                "an uncommitted WriteBatch is in-memory only — a crash loses it cleanly");
    }

    @Test
    void a_crash_loses_only_the_in_flight_transaction(@TempDir Path dir) throws Exception {
        crash(dir, "commit-begin-crash");
        assertEquals(
                3L,
                sizeAfterReopen(dir),
                "earlier commits survive; only the in-flight transaction is lost");
    }
}
