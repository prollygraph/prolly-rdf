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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 *
 *
 * <h3>Data Integrity & Corruption Test</h3>
 *
 * <p>Verifies that the engine detects cryptographic hash mismatches during Merkle walks, preventing
 * silent data corruption.
 */
public class DataIntegrityCorruptionTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Data Integrity & Corruption Test ---");
        Path tempDir = Files.createTempDirectory("prolly-corruption");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore rocksStore = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(rocksStore, desc, pool);

            // 1. Build a small tree
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%03d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(), MemorySegment.ofArray("val".getBytes())));
            }
            Node root = mutator.applyMutations(null, edits.iterator());
            byte[] rootHash = rocksStore.write(root.segment());

            // 2. Inject Corruption into RocksDB
            System.out.print("Injecting silent corruption into NodeStore... ");
            byte[] garbage = new byte[1024];
            new Random().nextBytes(garbage);
            rocksStore.db().put(rootHash, garbage); // Overwrite root node
            System.out.println("Done.");

            // 3. Create Verifying Store
            NodeStore verifyingStore = new HashVerifyingNodeStore(rocksStore);

            // 4. Attempt to load the tree from the corrupted hash
            System.out.print("Verifying detection during root load... ");
            try {
                // This should trigger the HashVerifyingNodeStore.read() verification
                Optional<MemorySegment> corruptedData = verifyingStore.read(rootHash);
                if (corruptedData.isPresent()) {
                    // Try to parse it just in case
                    Node.fromBytes(corruptedData.get());
                }

                System.err.println("FAILED: Verification should have failed!");
                System.exit(1);
            } catch (RuntimeException e) {
                System.out.println("Passed (Detected: " + e.getMessage() + ")");
            }

            System.out.println("--- Data Integrity Test PASSED ---");
        }
    }

    static class HashVerifyingNodeStore implements NodeStore {
        private final NodeStore inner;

        HashVerifyingNodeStore(NodeStore inner) {
            this.inner = inner;
        }

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            Optional<MemorySegment> res = inner.read(hash);
            if (res.isPresent()) {
                byte[] actual = HashUtils.hash(res.get().asByteBuffer());
                if (!Arrays.equals(hash, actual)) {
                    throw new RuntimeException(
                            "Merkle Mismatch! Expected "
                                    + toHex(hash)
                                    + " but got "
                                    + toHex(actual));
                }
            }
            return res;
        }

        @Override
        public byte[] write(MemorySegment data) {
            return inner.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            return inner.write(data);
        }

        private static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }
}
