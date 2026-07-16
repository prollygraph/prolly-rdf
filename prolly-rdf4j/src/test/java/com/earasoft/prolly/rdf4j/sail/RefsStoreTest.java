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

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefsStoreTest {

    private static byte[] hash(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        return out;
    }

    @Test
    void empty_store_returns_empty_list(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        assertTrue(refs.list().isEmpty());
        assertTrue(refs.get("main").isEmpty());
        assertFalse(refs.exists("main"));
    }

    @Test
    void put_then_get_round_trips(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        byte[] h = hash(0x42);
        refs.put("main", h);

        assertTrue(refs.exists("main"));
        assertArrayEquals(h, refs.get("main").orElseThrow());
    }

    @Test
    void put_overwrites_existing_branch(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        refs.put("main", hash(0x02));
        assertArrayEquals(hash(0x02), refs.get("main").orElseThrow());
    }

    @Test
    void list_returns_all_branches(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        refs.put("feature-x", hash(0x02));
        refs.put("release", hash(0x03));

        Map<String, byte[]> all = refs.list();
        assertEquals(3, all.size());
        assertArrayEquals(hash(0x01), all.get("main"));
        assertArrayEquals(hash(0x02), all.get("feature-x"));
        assertArrayEquals(hash(0x03), all.get("release"));
    }

    @Test
    void list_supports_nested_branch_names(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("feature/a", hash(0x01));
        refs.put("feature/b", hash(0x02));
        refs.put("main", hash(0x03));

        Map<String, byte[]> all = refs.list();
        assertEquals(3, all.size());
        assertTrue(all.containsKey("feature/a"));
        assertTrue(all.containsKey("feature/b"));
    }

    @Test
    void delete_removes_the_branch(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        refs.put("temp", hash(0x02));

        assertTrue(refs.delete("temp"));
        assertFalse(refs.delete("temp")); // already gone
        assertFalse(refs.exists("temp"));
        assertTrue(refs.exists("main"));
    }

    @Test
    void name_validation_rejects_dot_dot(@TempDir Path dir) {
        RefsStore refs = RefsStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> refs.put("..", hash(0x01)));
        assertThrows(IllegalArgumentException.class, () -> refs.put("foo/../bar", hash(0x01)));
    }

    @Test
    void name_validation_rejects_bad_chars(@TempDir Path dir) {
        RefsStore refs = RefsStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> refs.put("with space", hash(0x01)));
        assertThrows(IllegalArgumentException.class, () -> refs.put("semi;colon", hash(0x01)));
        assertThrows(IllegalArgumentException.class, () -> refs.put("", hash(0x01)));
    }

    @Test
    void atomic_rename_leaves_no_tmp_files(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        // Walk the refs dir — no .tmp files should remain after a successful put.
        try (var stream = java.nio.file.Files.walk(refs.dir())) {
            assertEquals(
                    0,
                    stream.filter(p -> p.toString().endsWith(".tmp")).count(),
                    "atomic put must not leak .tmp sidecars");
        }
    }

    // ---- durability / crash safety ----

    @Test
    void get_of_a_corrupt_ref_file_surfaces_a_clear_IOException(@TempDir Path dir)
            throws Exception {
        // Bit rot / a manual edit leaves non-hex content in a branch ref file.
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        java.nio.file.Files.writeString(refs.dir().resolve("main"), "not-a-hex-hash-zz");

        java.io.IOException e = assertThrows(java.io.IOException.class, () -> refs.get("main"));
        assertTrue(
                e.getMessage().contains("corrupt"),
                "a corrupt ref fails loudly, naming the branch — not a raw NumberFormatException");
    }

    @Test
    void get_of_a_blank_ref_file_reads_as_unset(@TempDir Path dir) throws Exception {
        // A partial fsync can leave a ref file present but empty.
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        java.nio.file.Files.writeString(refs.dir().resolve("main"), "  \n");
        assertTrue(
                refs.get("main").isEmpty(),
                "a blank ref file is treated as an unset branch, not a corrupt one");
    }

    @Test
    void a_stale_tmp_from_a_crashed_put_is_ignored_by_get_list_and_a_later_put(@TempDir Path dir)
            throws Exception {
        // put() writes "<name>.tmp" then atomically renames. A crash between
        // the two steps orphans a .tmp; it must not show up as a branch nor
        // block a subsequent put.
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        java.nio.file.Files.writeString(refs.dir().resolve("main.tmp"), "orphaned partial write");

        assertEquals(1, refs.list().size(), "list() ignores the stale .tmp sidecar");
        assertArrayEquals(
                hash(0x01),
                refs.get("main").orElseThrow(),
                "get() reads the committed ref, not the orphaned .tmp");

        refs.put("main", hash(0x02));
        assertArrayEquals(
                hash(0x02),
                refs.get("main").orElseThrow(),
                "a later put() still commits atomically despite the orphaned .tmp");
    }

    // ---- compareAndSet (plan Step 21) -------------------------------------

    @Test
    void compareAndSet_creates_a_branch_when_expected_is_null(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        assertTrue(refs.compareAndSet("feature", null, hash(0x10)));
        assertArrayEquals(hash(0x10), refs.get("feature").orElseThrow());
    }

    @Test
    void compareAndSet_with_null_expected_fails_when_branch_already_exists(@TempDir Path dir)
            throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        assertFalse(
                refs.compareAndSet("main", null, hash(0x02)),
                "null expected means create-only; an existing branch must lose the CAS");
        assertArrayEquals(
                hash(0x01), refs.get("main").orElseThrow(), "the existing value is preserved");
    }

    @Test
    void compareAndSet_updates_when_expected_matches(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x01));
        assertTrue(refs.compareAndSet("main", hash(0x01), hash(0x02)));
        assertArrayEquals(hash(0x02), refs.get("main").orElseThrow());
    }

    @Test
    void compareAndSet_with_stale_expected_returns_false_and_leaves_value_untouched(
            @TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("main", hash(0x05));
        assertFalse(refs.compareAndSet("main", hash(0x01), hash(0x02)));
        assertArrayEquals(hash(0x05), refs.get("main").orElseThrow());
    }

    @Test
    void compareAndSet_with_null_desired_is_rejected(@TempDir Path dir) {
        RefsStore refs = RefsStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> refs.compareAndSet("main", null, null));
    }

    // ---- compareAndDelete (TOCTOU-safe branch delete) ----------------------

    @Test
    void compareAndDelete_removes_branch_when_head_matches(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        refs.put("feature", hash(0x01));
        assertTrue(refs.compareAndDelete("feature", hash(0x01)));
        assertTrue(refs.get("feature").isEmpty());
    }

    @Test
    void compareAndDelete_skips_when_head_was_advanced(@TempDir Path dir) throws Exception {
        // Simulates the merge-then-someone-pushed-more race:
        // merge captured head=0x01, but a concurrent push moved it to 0x02
        // before the auto-delete fired. compareAndDelete must NOT drop the
        // branch (commits at 0x02 would be lost).
        RefsStore refs = RefsStore.beside(dir);
        refs.put("feature", hash(0x02));
        assertFalse(
                refs.compareAndDelete("feature", hash(0x01)),
                "stale expected head must NOT delete the branch");
        assertArrayEquals(hash(0x02), refs.get("feature").orElseThrow());
    }

    @Test
    void compareAndDelete_returns_false_when_branch_missing(@TempDir Path dir) throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        assertFalse(refs.compareAndDelete("never-existed", hash(0x01)));
    }

    @Test
    void compareAndDelete_rejects_null_expected(@TempDir Path dir) {
        RefsStore refs = RefsStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> refs.compareAndDelete("feature", null));
    }

    @Test
    void compareAndSet_lock_file_does_not_appear_as_a_branch_in_list(@TempDir Path dir)
            throws Exception {
        // The sidecar lockfile must live *outside* refs/ so it never surfaces
        // through list(). A regression here would silently inject ".cas.lock"
        // into every branches API response.
        RefsStore refs = RefsStore.beside(dir);
        refs.compareAndSet("main", null, hash(0x01));
        refs.compareAndSet("main", hash(0x01), hash(0x02));

        assertEquals(1, refs.list().size(), "only 'main' is listed: " + refs.list().keySet());
        assertTrue(refs.list().containsKey("main"));
    }

    @Test
    void concurrent_compareAndSet_admits_exactly_one_winner(@TempDir Path dir) throws Exception {
        // N threads each try CAS(null → uniqueValue). Only one CAS should
        // win, every other must return false — and the surviving value must
        // be one of the candidates, not a torn write.
        RefsStore refs = RefsStore.beside(dir);
        int n = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        var start = new java.util.concurrent.CountDownLatch(1);
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < n; i++) {
            byte[] desired = hash(0x40 + i);
            tasks.add(
                    pool.submit(
                            () -> {
                                start.await();
                                if (refs.compareAndSet("main", null, desired))
                                    winners.incrementAndGet();
                                return null;
                            }));
        }
        start.countDown();
        for (var t : tasks) t.get();
        pool.shutdown();
        assertEquals(1, winners.get(), "exactly one of " + n + " concurrent CAS-creates must win");
        byte[] survivor = refs.get("main").orElseThrow();
        boolean recognized = false;
        for (int i = 0; i < n; i++) {
            if (java.util.Arrays.equals(survivor, hash(0x40 + i))) {
                recognized = true;
                break;
            }
        }
        assertTrue(recognized, "the surviving value must be one of the candidates, not torn");
    }
}
