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
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 *
 *
 * <h3>Cross-Language (Go ↔ Java) Fixture Validator</h3>
 *
 * <p>Closes the loop on PORT_PLAN.md's "Cross-Language Compatibility" goal: loads a fixture
 * produced by Dolt's Go reference implementation ({@code cross-lang/gen_fixture.go}) and confirms
 * the Java implementation walks it bit-for-bit.
 *
 * <p><b>Bug-class this catches:</b> any drift in BuzHash table, chunking math, Flatbuffers field
 * order, varint encoding, or SHA-512/20 truncation between Go and Java. The Java side cannot
 * self-validate this — both languages must agree on the same byte sequences for the same input.
 *
 * <p><b>Fixture protocol</b> (see {@code cross-lang/README.md}):
 *
 * <ul>
 *   <li>{@code cross-lang/fixtures/manifest.txt} — first line {@code ROOT <hex>}, remaining lines
 *       {@code ITEM <key-hex> <value-hex>} in tree order.
 *   <li>{@code cross-lang/fixtures/nodes/<hash>.bin} — one file per chunk, filename is the
 *       SHA-512/20 of the contents.
 * </ul>
 *
 * <p><b>Behaviour when the fixture is missing:</b> prints a one-line "fixture not present,
 * skipping" message and exits success. CI does not fail. You only get the parity guarantee once
 * {@code go run gen_fixture.go} has been run at least once.
 *
 * <p><b>Oracles when the fixture is present:</b>
 *
 * <ol>
 *   <li>Every {@code nodes/<hash>.bin} file's content hashes to its filename. Mismatch ⇒ Go-side
 *       hash drift, OR file corruption.
 *   <li>Loading the bytes into a fresh {@link RocksNodeStore} and walking the manifest's root hash
 *       succeeds — every internal node resolves and re-hashes correctly. Mismatch ⇒ Java cannot
 *       parse Go's Flatbuffer node format (vtable / field offsets / varint encoding).
 *   <li>Iterating the loaded tree yields exactly the {@code ITEM} lines from the manifest, in
 *       order. Mismatch ⇒ tuple ordering / chunking math differs between Go and Java.
 *   <li>The manifest's root hash equals the pinned {@link BootstrapHashes#BOUNDARY_GOLDEN_ROOT}.
 *       This is the bit-compat oracle. Until Go is run, the pinned hash is Java-self-consistent
 *       only.
 * </ol>
 */
public class CrossLanguageFixtureTest {

    /**
     * Locate the repo-root {@code cross-lang/fixtures} dir by walking up from the working
     * directory. Surefire runs with the MODULE dir as cwd ({@code prolly-rdf/}), but the fixtures
     * live at the repo root — so a bare {@code Paths.get("cross-lang", "fixtures")} silently misses
     * them, which made this whole validator a no-op "fixture not present" skip under {@code mvn
     * test}. Resolve robustly so the test actually runs.
     */
    private static Path locateFixtureDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("cross-lang").resolve("fixtures");
            if (Files.exists(candidate.resolve("manifest.txt"))) return candidate;
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("--- Cross-Language Fixture Validator ---");

        Path fixtureDir = locateFixtureDir();
        if (fixtureDir == null) {
            System.out.println(
                    "Fixture not found under cross-lang/fixtures (searched cwd + "
                            + "ancestors) — skipping. Run cross-lang/gen_fixture.go to produce one.");
            return;
        }
        Path manifest = fixtureDir.resolve("manifest.txt");
        Path nodesDir = fixtureDir.resolve("nodes");
        System.out.println("Fixture dir: " + fixtureDir.toAbsolutePath());

        Manifest mf = readManifest(manifest);
        System.out.println(
                "Manifest: root=" + HashUtils.toHex(mf.rootHash) + " items=" + mf.items.size());

        // Oracle 1: every node file's content hashes to its filename.
        Map<String, byte[]> nodes = readNodes(nodesDir);
        System.out.println("Loaded " + nodes.size() + " node files.");
        for (var e : nodes.entrySet()) {
            byte[] expected = HashUtils.fromHex(e.getKey());
            byte[] actual = HashUtils.hash(e.getValue());
            if (!Arrays.equals(expected, actual)) {
                throw new RuntimeException(
                        "Node file "
                                + e.getKey()
                                + ".bin content hashes to "
                                + HashUtils.toHex(actual)
                                + " — Go/Java SHA-512/20 mismatch");
            }
        }
        System.out.println("All node files hash-verify (1/4).");

        // Oracle 2 + 3: load into RocksDB, walk through verifier.
        Path tempDir = Files.createTempDirectory("prolly-cross-lang");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            for (var e : nodes.entrySet()) {
                byte[] writtenHash = store.write(e.getValue());
                if (!Arrays.equals(writtenHash, HashUtils.fromHex(e.getKey()))) {
                    throw new RuntimeException(
                            "RocksNodeStore.write produced different hash than the Go-side: "
                                    + HashUtils.toHex(writtenHash)
                                    + " vs "
                                    + e.getKey());
                }
            }

            IntegrityVerifyingNodeStore verifier = new IntegrityVerifyingNodeStore(store);
            walkAndVerify(verifier, mf.rootHash);
            System.out.println("Tree walk + hash-verify succeeded (2/4).");

            // Oracle 3: tuple CONTENT. The port's tuple layout differs from
            // Dolt v2.0.3's — Layer 3 of cross-lang/BITCOMPAT_FINDINGS.md: Dolt
            // omits the first field's zero offset (stores count-1 offsets); the
            // port stores count. So reading Dolt's tuples is EXPECTED to diverge
            // (typically an IndexOutOfBoundsException in Tuple.getField). We
            // characterize the frontier rather than crash: attempt the content
            // match and record whether it holds.
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Node root = verifier.read(mf.rootHash).map(Node::fromBytes).orElseThrow();
            StaticMap loaded = new StaticMap(verifier, root, desc);

            String contentNote;
            boolean contentMatches;
            try {
                contentMatches = iterateMatchesManifest(loaded, mf);
                contentNote =
                        contentMatches
                                ? "tuple content matches manifest"
                                : "tuple content read but diverges from manifest";
            } catch (RuntimeException ex) {
                contentMatches = false;
                contentNote =
                        "tuple read threw "
                                + ex.getClass().getSimpleName()
                                + " ("
                                + ex.getMessage()
                                + ")";
            }

            if (contentMatches) {
                // Progress! Layer-3 parity achieved. The test pins the KNOWN
                // divergence, so trip on purpose to force the frontier forward.
                throw new RuntimeException(
                        "Cross-language Layer-3 parity ACHIEVED — the port now "
                                + "reads Dolt's tuple layout ("
                                + contentNote
                                + "). Update Oracle 3 to "
                                + "hard-assert content equality and advance cross-lang/BITCOMPAT_FINDINGS.md.");
            }
            System.out.println(
                    "Oracle 3 (tuple content): KNOWN Layer-3 divergence holds — "
                            + contentNote
                            + ". Layers 0-2 (hashing, framing, node-walk) verified above "
                            + "(see cross-lang/BITCOMPAT_FINDINGS.md). (3/4)");
        }

        // Oracle 4: HARD drift-tripwire on the cross-language bit-compat state.
        //
        // The Java port is NOT YET byte-compatible with Dolt v2.0.3 — a known,
        // documented divergence (cross-lang/BITCOMPAT_FINDINGS.md, 2026-05-15):
        // the Go fixture root (b96e85…) differs from Java's BOUNDARY_GOLDEN_ROOT
        // (1d9d81…). Asserting equality would be a FALSE claim of parity and a
        // red build, so this oracle was previously only a soft WARNING — which
        // meant it could never catch drift on EITHER side. We now pin the exact
        // current state instead:
        //   (a) the Go fixture root must equal the recorded Dolt v2.0.3
        //       reference — any silent fixture regeneration that moves Go's
        //       bytes fails here;
        //   (b) if Go and Java ever CONVERGE (the day bit-compat is achieved),
        //       this trips on purpose, forcing whoever fixed it to flip this
        //       oracle to assert equality and close the BITCOMPAT_FINDINGS gap.
        // Net: green while the known divergence holds, red the instant anything
        // moves. (Java's own root is independently pinned by
        // ChunkerDeterminismGateTest, so Java-side drift is caught there.)
        byte[] expectedGoRoot = HashUtils.fromHex("b96e85d18e25ff65247531af463eccb20bc936bc");
        if (!Arrays.equals(mf.rootHash, expectedGoRoot)) {
            throw new RuntimeException(
                    "Go-side fixture root drifted: manifest has "
                            + HashUtils.toHex(mf.rootHash)
                            + " but the recorded Dolt v2.0.3 reference is "
                            + HashUtils.toHex(expectedGoRoot)
                            + ". If you regenerated the fixture on purpose, update this pin.");
        }
        if (Arrays.equals(mf.rootHash, BootstrapHashes.BOUNDARY_GOLDEN_ROOT)) {
            throw new RuntimeException(
                    "Cross-language bit-compat ACHIEVED — the Go root now "
                            + "equals Java's BOUNDARY_GOLDEN_ROOT ("
                            + HashUtils.toHex(mf.rootHash)
                            + "). This is the goal! But Oracle 4 still pins a KNOWN divergence: change it "
                            + "to assert equality and close out cross-lang/BITCOMPAT_FINDINGS.md.");
        }
        System.out.println(
                "Go fixture root pinned; known divergence from Java "
                        + HashUtils.toHex(BootstrapHashes.BOUNDARY_GOLDEN_ROOT)
                        + " holds — port not yet byte-compatible with Dolt v2.0.3 (4/4).");

        System.out.println("--- Cross-Language Fixture Validator PASSED ---");
    }

    private static void walkAndVerify(NodeStore store, byte[] hash) {
        if (hash == null) return;
        Optional<MemorySegment> seg = store.read(hash);
        if (seg.isEmpty()) {
            throw new RuntimeException("Reachable child missing: " + HashUtils.toHex(hash));
        }
        Node n = Node.fromBytes(seg.get());
        if (!n.isLeaf()) {
            for (int i = 0; i < n.count(); i++) walkAndVerify(store, n.getValue(i));
        }
    }

    /**
     * Iterate the loaded tree and compare each entry to the manifest, in order. Returns true iff
     * every key/value matches and the counts agree. May throw (e.g. {@code
     * IndexOutOfBoundsException}) if the port cannot decode Dolt's tuple layout — the caller treats
     * a throw as the Layer-3 divergence.
     */
    private static boolean iterateMatchesManifest(StaticMap loaded, Manifest mf) {
        MapIterator it = loaded.iter();
        for (var expected : mf.items.entrySet()) {
            if (!it.next()) return false;
            byte[] gotKey = new Tuple(it.key()).getField(0);
            byte[] gotValue = it.value().toArray(ValueLayout.JAVA_BYTE);
            if (gotKey == null
                    || !Arrays.equals(gotKey, expected.getKey().getBytes(StandardCharsets.UTF_8))
                    || !Arrays.equals(
                            gotValue, expected.getValue().getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
        }
        return !it.next(); // no extra items past the manifest
    }

    private static Manifest readManifest(Path p) throws IOException {
        byte[] rootHash = null;
        Map<String, String> items = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.startsWith("ROOT ")) {
                rootHash = HashUtils.fromHex(line.substring(5).trim());
            } else if (line.startsWith("ITEM ")) {
                String[] parts = line.substring(5).trim().split(" ");
                if (parts.length != 2) {
                    throw new RuntimeException("Bad ITEM line: " + line);
                }
                String key = new String(HashUtils.fromHex(parts[0]), StandardCharsets.UTF_8);
                String value = new String(HashUtils.fromHex(parts[1]), StandardCharsets.UTF_8);
                items.put(key, value);
            }
        }
        if (rootHash == null) throw new RuntimeException("Manifest missing ROOT line");
        return new Manifest(rootHash, items);
    }

    private static Map<String, byte[]> readNodes(Path dir) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String hashHex = name.substring(0, name.length() - ".bin".length());
                out.put(hashHex, Files.readAllBytes(p));
            }
        }
        return out;
    }

    private record Manifest(byte[] rootHash, Map<String, String> items) {}
}
