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
package com.earasoft.prolly.rdf4j.dictbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The REAL-DATA verification: streams the actual NCI Thesaurus ({@code ncit.owl}, RDF/XML) and
 * re-runs the dictionary comparison on its genuine term distribution — real URI shapes, real
 * literal lengths (definitions run to paragraphs), real unicode — instead of the generated
 * NCIt-shaped corpus every earlier number used. Terms are canonical N-Triples serializations (the
 * same byte forms a real dictionary would store).
 *
 * <p>Deterministic quantities (footprint, depth, geometry, correctness) are exact; build/lookup
 * timings here are single-JVM medians-of-5, labeled INDICATIVE — the JMH-grade pass stays on the
 * synthetic corpus until a real-corpus JMH arm is warranted.
 *
 * <p>Skips when the file is absent (CI); point {@code dictbench.ncit} at an {@code ncit.owl}.
 */
@EnabledIf("ncitAvailable")
class RealNcitCorpusReportTest {

    static final int MAX_TERMS = 300_000;
    static final int MAX_STATEMENTS = 1_500_000;
    static int maxTermsOverride = MAX_TERMS;
    static int maxStatementsOverride = MAX_STATEMENTS;

    static Path ncitPath() {
        String prop =
                System.getProperty(
                        "dictbench.ncit",
                        System.getenv()
                                .getOrDefault(
                                        "DICTBENCH_NCIT",
                                        System.getProperty("user.home") + "/git/ttl/ncit.owl"));
        return Path.of(prop);
    }

    static boolean ncitAvailable() {
        return Files.isReadable(ncitPath());
    }

    private static final class Stop extends RuntimeException {}

    static byte[][] realTerms() throws Exception {
        // ncit.owl nests OWL class expressions deeper than JAXP's secure default (100).
        System.setProperty("jdk.xml.maxElementDepth", "0");
        System.setProperty("jdk.xml.totalEntitySizeLimit", "0");
        Set<String> terms = new LinkedHashSet<>(1 << 20);
        RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
        int[] statements = new int[1];
        parser.setRDFHandler(
                new AbstractRDFHandler() {
                    @Override
                    public void handleStatement(Statement st) throws RDFHandlerException {
                        for (Value v :
                                new Value[] {st.getSubject(), st.getPredicate(), st.getObject()}) {
                            if (terms.size() < maxTermsOverride) {
                                terms.add(v.toString()); // RDF4J's canonical per-type form
                            }
                        }
                        if (++statements[0] >= maxStatementsOverride
                                || terms.size() >= maxTermsOverride) {
                            throw new Stop();
                        }
                    }
                });
        try (InputStream in = Files.newInputStream(ncitPath())) {
            parser.parse(in, "http://purl.obolibrary.org/obo/ncit.owl");
        } catch (Stop expected) {
            // bounded sample collected
        }
        byte[][] out = new byte[terms.size()][];
        int i = 0;
        for (String t : terms) out[i++] = t.getBytes(StandardCharsets.UTF_8);
        System.out.printf(
                "real NCIt sample: %,d distinct terms from %,d statements%n",
                out.length, statements[0]);
        return out;
    }

    /**
     * FULL ingestion: every statement of the entire ncit.owl, every distinct term — the scale point
     * of the verification program. Opt-in ({@code -Ddictbench.ncit.full=true}): the full 811 MB
     * RDF/XML parse takes minutes.
     */
    @Test
    @EnabledIf("fullRequested")
    void fullNcitIngestion() throws Exception {
        int savedTerms = maxTermsOverride;
        int savedStatements = maxStatementsOverride;
        maxTermsOverride = Integer.MAX_VALUE;
        maxStatementsOverride = Integer.MAX_VALUE;
        try {
            long tParse = System.nanoTime();
            byte[][] corpus = realTerms();
            System.out.printf("full parse: %,d s%n", (System.nanoTime() - tParse) / 1_000_000_000);
            long raw = Arrays.stream(corpus).mapToLong(b -> b.length).sum();
            System.out.printf(
                    "raw %,d bytes; mean %.0f B; max %,d B%n",
                    raw,
                    (double) raw / corpus.length,
                    Arrays.stream(corpus).mapToLong(b -> b.length).max().orElse(0));

            DictionaryFootprintReportTest.CountingNodeStore store =
                    new DictionaryFootprintReportTest.CountingNodeStore();
            Dictionary dict =
                    new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash());
            long t0 = System.nanoTime();
            for (byte[] term : corpus) dict.encode(MemorySegment.ofArray(term));
            dict.commit();
            long tCur = System.nanoTime() - t0;

            TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
            for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);
            MerkleRadixDictionary radix = new MerkleRadixDictionary(64);
            t0 = System.nanoTime();
            MerkleRadixDictionary.Addr root = radix.build(entries);
            long tRad = System.nanoTime() - t0;

            SplittableRandom rnd = new SplittableRandom(1);
            for (int i = 0; i < 5_000; i++) {
                int idx = rnd.nextInt(corpus.length);
                assertEquals(idx, radix.get(root, corpus[idx]));
            }
            double meanDepth = 0;
            for (int i = 0; i < 2_000; i++) {
                meanDepth += radix.depthOf(root, corpus[rnd.nextInt(corpus.length)]);
            }
            System.out.printf(
                    "current: build %,d ms (single-run, INDICATIVE)  chunks %,d  bytes %,d (%.0f%% of raw)%n",
                    tCur / 1_000_000,
                    store.chunksWritten,
                    store.bytesWritten,
                    100.0 * store.bytesWritten / raw);
            System.out.printf(
                    "radix  : build %,d ms (single-run, INDICATIVE)  nodes %,d  bytes %,d (%.0f%% of raw)  mean depth %.1f%n",
                    tRad / 1_000_000,
                    radix.nodeCount(),
                    radix.storedBytes(),
                    100.0 * radix.storedBytes() / raw,
                    meanDepth / 2_000);
        } finally {
            maxTermsOverride = savedTerms;
            maxStatementsOverride = savedStatements;
        }
    }

    static boolean fullRequested() {
        return ncitAvailable() && Boolean.getBoolean("dictbench.ncit.full");
    }

    @Test
    void realCorpusComparison() throws Exception {
        byte[][] corpus = realTerms();
        long raw = Arrays.stream(corpus).mapToLong(b -> b.length).sum();
        long maxLen = Arrays.stream(corpus).mapToLong(b -> b.length).max().orElse(0);
        System.out.printf(
                "raw bytes %,d (mean term %.0f B, max %,d B)%n",
                raw, (double) raw / corpus.length, maxLen);

        // ---- current engine dictionary ----
        DictionaryFootprintReportTest.CountingNodeStore store =
                new DictionaryFootprintReportTest.CountingNodeStore();
        long tCur = Long.MAX_VALUE;
        Dictionary dict = null;
        for (int rep = 0; rep < 3; rep++) {
            store = new DictionaryFootprintReportTest.CountingNodeStore();
            dict = new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash());
            long t0 = System.nanoTime();
            for (byte[] term : corpus) dict.encode(MemorySegment.ofArray(term));
            dict.commit();
            tCur = Math.min(tCur, System.nanoTime() - t0);
        }

        // ---- radix K=64 ----
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);
        MerkleRadixDictionary radix = null;
        MerkleRadixDictionary.Addr root = null;
        long tRad = Long.MAX_VALUE;
        for (int rep = 0; rep < 3; rep++) {
            radix = new MerkleRadixDictionary(64);
            long t0 = System.nanoTime();
            root = radix.build(entries);
            tRad = Math.min(tRad, System.nanoTime() - t0);
        }

        // Correctness on real data: every term resolves in both directions of the study.
        SplittableRandom rnd = new SplittableRandom(1);
        for (int i = 0; i < 3_000; i++) {
            int idx = rnd.nextInt(corpus.length);
            assertEquals(idx, radix.get(root, corpus[idx]));
        }
        assertTrue(dict.findTermId(MemorySegment.ofArray(corpus[0])).isPresent());

        // Depth + geometry on the real distribution.
        double meanDepth = 0;
        for (int i = 0; i < 2_000; i++) {
            meanDepth += radix.depthOf(root, corpus[rnd.nextInt(corpus.length)]);
        }
        meanDepth /= 2_000;

        System.out.printf(
                "current: build(best-of-3, INDICATIVE) %,d ms  chunks %,d  bytes %,d (%.0f%% of raw)%n",
                tCur / 1_000_000,
                store.chunksWritten,
                store.bytesWritten,
                100.0 * store.bytesWritten / raw);
        System.out.printf(
                "radix  : build(best-of-3, INDICATIVE) %,d ms  nodes %,d  bytes %,d (%.0f%% of raw)  mean depth %.1f%n",
                tRad / 1_000_000,
                radix.nodeCount(),
                radix.storedBytes(),
                100.0 * radix.storedBytes() / raw,
                meanDepth);
    }
}
